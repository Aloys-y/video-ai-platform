package com.videoai.rag.service;

import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.model.ChunkedSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 知识卡片分块服务。
 *
 * 策略：按文档层级切分（heading-first, paragraph-fallback），不做纯字符截断。
 * - ## 标题 → 首选边界
 * - ### 标题 → 二级边界
 * - 段落（连续非空行块） → 兜底边界
 * - 键值行（Key | Value）作为独立段落处理，不拆分
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkingService {

    private static final Pattern H1_PATTERN = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern H2_PATTERN = Pattern.compile("^##\\s+(.+)$");
    private static final Pattern H3_PATTERN = Pattern.compile("^###\\s+(.+)$");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,3}\\s+(.+)$");

    private final RagProperties ragProperties;

    public List<ChunkedSegment> chunkMarkdown(String title, String markdown) {
        List<ChunkedSegment> result = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return result;
        }

        String cleaned = stripFrontmatter(markdown);
        List<Paragraph> paragraphs = parseParagraphs(cleaned);

        String h1Heading = title;
        String h2Heading = null;
        StringBuilder current = new StringBuilder();
        int chunkNo = 0;

        for (Paragraph p : paragraphs) {
            if (p.type == ParagraphType.H1) {
                if (current.length() > 0) {
                    result.addAll(splitIfNeeded(current.toString(), title,
                            buildHeadingPath(title, h2Heading), chunkNo));
                    chunkNo = result.size();
                    current = new StringBuilder();
                }
                h1Heading = p.content;
                h2Heading = null;
                continue;
            }

            if (p.type == ParagraphType.H2) {
                if (current.length() > 0) {
                    result.addAll(splitIfNeeded(current.toString(), title,
                            buildHeadingPath(title, h2Heading), chunkNo));
                    chunkNo = result.size();
                    current = new StringBuilder();
                }
                h2Heading = p.content;
                continue;
            }

            if (p.type == ParagraphType.H3) {
                if (current.length() >= targetMin() && current.length() + p.content.length() > targetMax()) {
                    result.addAll(splitIfNeeded(current.toString(), title,
                            buildHeadingPath(title, h2Heading), chunkNo));
                    chunkNo = result.size();
                    current = new StringBuilder();
                }
                h2Heading = (h2Heading != null ? h2Heading + " > " : "") + p.content;
                current.append(p.content).append('\n');
                continue;
            }

            if (current.length() + p.content.length() > targetMax() && current.length() >= targetMin()) {
                result.addAll(splitIfNeeded(current.toString(), title,
                        buildHeadingPath(title, h2Heading), chunkNo));
                chunkNo = result.size();
                current = new StringBuilder();
            }

            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(p.content);
        }

        if (current.length() > 0) {
            result.addAll(splitIfNeeded(current.toString(), title,
                    buildHeadingPath(title, h2Heading), chunkNo));
        }

        return result;
    }

    /**
     * 如果内容超过最大值，先按空行块/句子边界切，相邻 chunk 做 overlap 防边界语义断裂。
     */
    private List<ChunkedSegment> splitIfNeeded(String content, String title,
                                                String headingPath, int startChunkNo) {
        List<ChunkedSegment> segments = new ArrayList<>();
        String trimmed = content.trim();

        // Short enough — return as-is
        if (trimmed.length() <= targetMax()) {
            segments.add(buildSegment(startChunkNo, title, headingPath, trimmed));
            return segments;
        }

        // 1. 切成原始文本片段（空行块优先，句子边界兜底）
        List<String> rawChunks = splitToRawChunks(trimmed);

        // 2. 相邻片段做 overlap（同章节内被迫切开的块，防语义断裂）
        int overlap = ragProperties.getChunkOverlapChars();
        int chunkNo = startChunkNo;
        String prevRaw = null;
        for (String raw : rawChunks) {
            String chunkText = raw;
            if (prevRaw != null && overlap > 0) {
                String tail = prevRaw.substring(Math.max(0, prevRaw.length() - overlap));
                chunkText = tail + "\n" + raw;
            }
            segments.add(buildSegment(chunkNo++, title, headingPath, chunkText));
            prevRaw = raw;
        }
        return segments;
    }

    /**
     * 把长文本切成原始片段。优先级：空行块（段落）> 句子边界 > 硬切。
     */
    private List<String> splitToRawChunks(String text) {
        List<String> result = new ArrayList<>();
        String[] blocks = text.split("\\n\\s*\\n");
        for (String block : blocks) {
            String b = block.trim();
            if (b.isEmpty()) continue;
            if (b.length() <= targetMax()) {
                result.add(b);
            } else {
                result.addAll(splitBySentence(b));
            }
        }
        return result;
    }

    /**
     * 单个块超长时，优先按句子边界（句号/换行）切；单句超长才硬切。
     */
    private List<String> splitBySentence(String text) {
        List<String> result = new ArrayList<>();
        int max = targetMax();

        // 按句号后的空白、或换行边界切句子
        String[] sentences = text.split("(?<=[.!?。！？])\\s+|\\n+");
        StringBuilder chunk = new StringBuilder();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;

            if (chunk.length() + s.length() > max && chunk.length() > 0) {
                result.add(chunk.toString().trim());
                chunk = new StringBuilder();
            }

            if (s.length() > max) {
                // 单个句子还超长，只能硬切
                if (chunk.length() > 0) {
                    result.add(chunk.toString().trim());
                    chunk = new StringBuilder();
                }
                for (int i = 0; i < s.length(); i += max) {
                    result.add(s.substring(i, Math.min(i + max, s.length())));
                }
            } else {
                if (chunk.length() > 0) chunk.append(' ');
                chunk.append(s);
            }
        }
        if (chunk.length() > 0) result.add(chunk.toString().trim());
        return result;
    }

    private String buildHeadingPath(String title, String sectionHeading) {
        if (sectionHeading == null || sectionHeading.isBlank()) {
            return title;
        }
        return title + " > " + sectionHeading;
    }

    private ChunkedSegment buildSegment(int chunkNo, String title, String headingPath, String content) {
        return ChunkedSegment.builder()
                .chunkNo(chunkNo)
                .title(title)
                .headingPath(headingPath)
                .contentText(content)
                .build();
    }

    // ---- frontmatter stripping ----

    private String stripFrontmatter(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("---", 3);
            if (end > 0) {
                return trimmed.substring(end + 3).trim();
            }
        }
        return trimmed;
    }

    // ---- paragraph parsing ----

    private List<Paragraph> parseParagraphs(String text) {
        List<Paragraph> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        ParagraphType currentType = null;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (current.length() > 0) {
                    result.add(new Paragraph(currentType, current.toString().trim()));
                    current = new StringBuilder();
                    currentType = null;
                }
                continue;
            }

            Matcher h1 = H1_PATTERN.matcher(line);
            if (h1.matches()) {
                if (current.length() > 0) {
                    result.add(new Paragraph(currentType, current.toString().trim()));
                    current = new StringBuilder();
                }
                result.add(new Paragraph(ParagraphType.H1, h1.group(1).trim()));
                currentType = null;
                continue;
            }

            Matcher h2 = H2_PATTERN.matcher(line);
            if (h2.matches()) {
                if (current.length() > 0) {
                    result.add(new Paragraph(currentType, current.toString().trim()));
                    current = new StringBuilder();
                }
                result.add(new Paragraph(ParagraphType.H2, h2.group(1).trim()));
                currentType = null;
                continue;
            }

            Matcher h3 = H3_PATTERN.matcher(line);
            if (h3.matches()) {
                if (current.length() > 0) {
                    result.add(new Paragraph(currentType, current.toString().trim()));
                    current = new StringBuilder();
                }
                result.add(new Paragraph(ParagraphType.H3, h3.group(1).trim()));
                currentType = null;
                continue;
            }

            ParagraphType lineType = classifyLine(line);
            if (currentType != null && currentType != lineType) {
                if (current.length() > 0) {
                    result.add(new Paragraph(currentType, current.toString().trim()));
                    current = new StringBuilder();
                }
            }

            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            currentType = lineType;
        }

        if (current.length() > 0) {
            result.add(new Paragraph(currentType, current.toString().trim()));
        }
        return result;
    }

    private ParagraphType classifyLine(String line) {
        // Infobox key-value pairs or table rows
        if (line.contains("|") && !line.startsWith("-") && !line.startsWith("#")) {
            return ParagraphType.KV_TABLE;
        }
        // List items
        if (line.startsWith("-") || line.startsWith("*")) {
            return ParagraphType.LIST;
        }
        return ParagraphType.PROSE;
    }

    private int targetMin() {
        return ragProperties.getChunkMinChars();
    }

    private int targetMax() {
        return ragProperties.getChunkMaxChars();
    }

    // ---- inner types ----

    private enum ParagraphType {
        H1, H2, H3, PROSE, KV_TABLE, LIST
    }

    private record Paragraph(ParagraphType type, String content) {}
}
