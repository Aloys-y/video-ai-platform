package com.videoai.rag.service;

import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.model.ChunkedSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 知识卡片分块服务。
 *
 * V2 策略：先按 H1/H2/H3 建立互不污染的章节，再在章节内部按目标长度组合句子和行。
 * 只有同一章节内被迫分块时才增加 overlap，并保证最终块不超过 max。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeChunkingService {

    private static final Pattern H1_PATTERN = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern H2_PATTERN = Pattern.compile("^##\\s+(.+)$");
    private static final Pattern H3_PATTERN = Pattern.compile("^###\\s+(.+)$");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?。！？])\\s+|(?=\\s*-\\s+)");
    private static final Pattern LOWER_TO_UPPER = Pattern.compile("(?<=[\\p{Ll}\\d])(?=[\\p{Lu}])");

    private final RagProperties ragProperties;

    public List<ChunkedSegment> chunkMarkdown(String title, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        List<Paragraph> paragraphs = parseParagraphs(stripFrontmatter(markdown));
        List<String> protectedTerms = protectedTerms(title, paragraphs);
        List<ChunkedSegment> result = new ArrayList<>();

        String h1 = null;
        String h2 = null;
        String h3 = null;
        StringBuilder section = new StringBuilder();

        for (Paragraph paragraph : paragraphs) {
            if (paragraph.type == ParagraphType.H1) {
                flushSection(result, title, h1, h2, h3, section, protectedTerms);
                h1 = paragraph.content;
                h2 = null;
                h3 = null;
                continue;
            }
            if (paragraph.type == ParagraphType.H2) {
                flushSection(result, title, h1, h2, h3, section, protectedTerms);
                h2 = paragraph.content;
                h3 = null;
                continue;
            }
            if (paragraph.type == ParagraphType.H3) {
                flushSection(result, title, h1, h2, h3, section, protectedTerms);
                h3 = paragraph.content;
                continue;
            }

            if (section.length() > 0) {
                section.append('\n');
            }
            section.append(paragraph.content);
        }
        flushSection(result, title, h1, h2, h3, section, protectedTerms);
        return result;
    }

    private void flushSection(List<ChunkedSegment> result, String title,
                              String h1, String h2, String h3,
                              StringBuilder section, List<String> protectedTerms) {
        if (section.length() == 0) {
            return;
        }
        String content = normalizeContent(section.toString(), protectedTerms);
        section.setLength(0);
        if (content.isBlank()) {
            return;
        }

        String headingPath = buildHeadingPath(title, h1, h2, h3);
        List<String> rawChunks = packToTarget(content);
        List<String> chunksWithOverlap = applyBoundedOverlap(rawChunks);
        for (String chunk : chunksWithOverlap) {
            if (!chunk.isBlank()) {
                result.add(buildSegment(result.size(), title, headingPath, chunk));
            }
        }
    }

    private List<String> packToTarget(String content) {
        List<String> units = semanticUnits(content);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int target = targetChars();
        int min = minChars();
        int max = maxChars();

        for (String unit : units) {
            int projected = current.length() + (current.length() == 0 ? 0 : 1) + unit.length();
            boolean targetReached = current.length() >= min && projected > target;
            boolean maxExceeded = projected > max;
            if (current.length() > 0 && (targetReached || maxExceeded)) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        mergeSmallTail(chunks);
        return chunks;
    }

    private List<String> semanticUnits(String content) {
        List<String> units = new ArrayList<>();
        for (String rawLine : content.split("\\R+")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            for (String sentence : SENTENCE_BOUNDARY.split(line)) {
                String value = sentence.trim();
                if (!value.isEmpty()) {
                    units.addAll(splitLongUnit(value, targetChars()));
                }
            }
        }
        return units;
    }

    private List<String> splitLongUnit(String value, int budget) {
        if (value.length() <= budget) {
            return List.of(value);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(start + budget, value.length());
            if (end < value.length()) {
                int boundary = lastBoundary(value, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String part = value.substring(start, end).trim();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            start = end;
            while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
                start++;
            }
        }
        return parts;
    }

    private int lastBoundary(String value, int start, int end) {
        for (int index = end; index > start; index--) {
            char c = value.charAt(index - 1);
            if (Character.isWhitespace(c) || c == ',' || c == ';' || c == '，' || c == '；') {
                return index;
            }
        }
        return end;
    }

    private void mergeSmallTail(List<String> chunks) {
        if (chunks.size() < 2) {
            return;
        }
        int lastIndex = chunks.size() - 1;
        String tail = chunks.get(lastIndex);
        String previous = chunks.get(lastIndex - 1);
        int overlapHeadroom = Math.max(0, ragProperties.getChunkOverlapChars()) + 1;
        int mergeLimit = Math.max(minChars(), maxChars() - overlapHeadroom);
        if (tail.length() < minChars() && previous.length() + 1 + tail.length() <= mergeLimit) {
            chunks.set(lastIndex - 1, previous + "\n" + tail);
            chunks.remove(lastIndex);
        }
    }

    private List<String> applyBoundedOverlap(List<String> rawChunks) {
        if (rawChunks.size() < 2 || ragProperties.getChunkOverlapChars() <= 0) {
            return rawChunks;
        }
        List<String> result = new ArrayList<>(rawChunks.size());
        result.add(rawChunks.get(0));
        for (int index = 1; index < rawChunks.size(); index++) {
            String raw = rawChunks.get(index);
            int available = Math.max(0, maxChars() - raw.length() - 1);
            int overlapBudget = Math.min(ragProperties.getChunkOverlapChars(), available);
            String tail = semanticTail(rawChunks.get(index - 1), overlapBudget);
            result.add(tail.isEmpty() ? raw : tail + "\n" + raw);
        }
        return result;
    }

    private String semanticTail(String previous, int budget) {
        if (budget <= 0 || previous.isEmpty()) {
            return "";
        }
        int start = Math.max(0, previous.length() - budget);
        if (start > 0) {
            for (int index = start; index < previous.length(); index++) {
                if (Character.isWhitespace(previous.charAt(index)) && index + 1 < previous.length()) {
                    start = index + 1;
                    break;
                }
            }
        }
        return previous.substring(start).trim();
    }

    private String normalizeContent(String content, List<String> protectedTerms) {
        String normalized = content.replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n");
        normalized = LOWER_TO_UPPER.matcher(normalized).replaceAll(" ");

        // Fandom 抽取器曾使用 get_text(strip=true)，会把链接词与前后正文粘连。
        // 标题和技能标题是高置信词，只在它们紧邻字母/数字时补边界。
        for (String term : protectedTerms) {
            String quoted = Pattern.quote(term);
            normalized = normalized.replaceAll("(?<=[\\p{L}\\p{N}])(" + quoted + ")", " $1");
            normalized = normalized.replaceAll("(" + quoted + ")(?=[\\p{Ll}\\d])", "$1 ");
        }
        return normalized.replaceAll(" {2,}", " ").trim();
    }

    private List<String> protectedTerms(String title, List<Paragraph> paragraphs) {
        Set<String> terms = new LinkedHashSet<>();
        if (title != null && title.trim().length() >= 3) {
            terms.add(title.trim());
        }
        for (Paragraph paragraph : paragraphs) {
            if ((paragraph.type == ParagraphType.H1
                    || paragraph.type == ParagraphType.H2
                    || paragraph.type == ParagraphType.H3)
                    && paragraph.content.length() >= 3) {
                terms.add(paragraph.content);
            }
        }
        return terms.stream().sorted((left, right) -> Integer.compare(right.length(), left.length())).toList();
    }

    private String buildHeadingPath(String title, String h1, String h2, String h3) {
        List<String> parts = new ArrayList<>();
        addPathPart(parts, title);
        if (h1 == null || title == null || !h1.equalsIgnoreCase(title.trim())) {
            addPathPart(parts, h1);
        }
        addPathPart(parts, h2);
        addPathPart(parts, h3);
        return String.join(" > ", parts);
    }

    private void addPathPart(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    private ChunkedSegment buildSegment(int chunkNo, String title, String headingPath, String content) {
        return ChunkedSegment.builder()
                .chunkNo(chunkNo)
                .title(title)
                .headingPath(headingPath)
                .contentText(content)
                .build();
    }

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

    private List<Paragraph> parseParagraphs(String text) {
        List<Paragraph> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        ParagraphType currentType = null;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                flushParagraph(result, currentType, current);
                currentType = null;
                continue;
            }

            Paragraph heading = parseHeading(line);
            if (heading != null) {
                flushParagraph(result, currentType, current);
                currentType = null;
                result.add(heading);
                continue;
            }

            ParagraphType lineType = classifyLine(line);
            if (currentType != null && currentType != lineType) {
                flushParagraph(result, currentType, current);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            currentType = lineType;
        }
        flushParagraph(result, currentType, current);
        return result;
    }

    private Paragraph parseHeading(String line) {
        Matcher h1 = H1_PATTERN.matcher(line);
        if (h1.matches()) return new Paragraph(ParagraphType.H1, h1.group(1).trim());
        Matcher h2 = H2_PATTERN.matcher(line);
        if (h2.matches()) return new Paragraph(ParagraphType.H2, h2.group(1).trim());
        Matcher h3 = H3_PATTERN.matcher(line);
        if (h3.matches()) return new Paragraph(ParagraphType.H3, h3.group(1).trim());
        return null;
    }

    private void flushParagraph(List<Paragraph> result, ParagraphType type, StringBuilder current) {
        if (current.length() > 0) {
            result.add(new Paragraph(type == null ? ParagraphType.PROSE : type, current.toString().trim()));
            current.setLength(0);
        }
    }

    private ParagraphType classifyLine(String line) {
        if (line.contains("|") && !line.startsWith("-") && !line.startsWith("#")) {
            return ParagraphType.KV_TABLE;
        }
        if (line.startsWith("-") || line.startsWith("*")) {
            return ParagraphType.LIST;
        }
        return ParagraphType.PROSE;
    }

    private int minChars() {
        return Math.max(1, Math.min(ragProperties.getChunkMinChars(), maxChars()));
    }

    private int maxChars() {
        return Math.max(1, ragProperties.getChunkMaxChars());
    }

    private int targetChars() {
        int overlapHeadroom = Math.max(0, ragProperties.getChunkOverlapChars()) + 1;
        int payloadMax = Math.max(1, maxChars() - overlapHeadroom);
        return Math.max(1, Math.min(ragProperties.getChunkTargetChars(), payloadMax));
    }

    private enum ParagraphType {
        H1, H2, H3, PROSE, KV_TABLE, LIST
    }

    private record Paragraph(ParagraphType type, String content) {
    }
}
