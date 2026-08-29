package com.videoai.rag.service;

import com.videoai.infra.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeChunkingServiceTest {

    @Test
    void shouldSplitMarkdownAndKeepHeadingPath() {
        RagProperties properties = new RagProperties();
        properties.setChunkMinChars(30);
        properties.setChunkMaxChars(60);
        properties.setChunkOverlapChars(10);

        KnowledgeChunkingService service = new KnowledgeChunkingService(properties);
        String markdown = """
                # 武器
                R301 适合中近距离持续压枪。
                对于新手来说，它的容错率较高，适合作为基础步枪练习对象。
                # 团战
                推进时要注意掩体与交叉火力，不要孤身前压。
                """;

        var result = service.chunkMarkdown("R301 卡片", markdown);

        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(item -> item.getHeadingPath().startsWith("R301 卡片")));
    }

    @Test
    void shouldOverlapAdjacentChunksWhenForcedToSplit() {
        RagProperties properties = new RagProperties();
        properties.setChunkMinChars(50);
        properties.setChunkTargetChars(90);
        properties.setChunkMaxChars(120);
        properties.setChunkOverlapChars(20);

        KnowledgeChunkingService service = new KnowledgeChunkingService(properties);
        // 超长无标题文本，会被迫切分成多块
        String longText = "这是一段用于测试分块逻辑的较长文本内容，用来验证 overlap 是否生效。".repeat(15);

        var result = service.chunkMarkdown("测试卡片", longText);

        assertTrue(result.size() >= 2, "应该切成多块，实际 " + result.size());

        // 相邻 chunk 有 overlap：后一个 chunk 以不截断词语的前块尾部开头
        for (int i = 1; i < result.size(); i++) {
            String prev = result.get(i - 1).getContentText();
            String curr = result.get(i).getContentText();
            int separator = curr.indexOf('\n');
            assertTrue(separator > 0, "chunk " + i + " 应包含 overlap 分隔行");
            String semanticTail = curr.substring(0, separator);
            assertTrue(semanticTail.length() <= 20);
            assertTrue(prev.endsWith(semanticTail),
                    "chunk " + i + " 开头应包含前一个 chunk 的语义尾部（overlap）");
        }
    }

    @Test
    void shouldKeepSiblingH3HeadingsAtTheSameLevel() {
        KnowledgeChunkingService service = service(20, 80, 120, 0);
        String markdown = """
                # Wraith
                ## Abilities
                ### Into the Void
                Tactical ability content with enough detail for one section.
                ### Voices from the Void
                Passive ability content with enough detail for another section.
                ### Dimensional Rift
                Ultimate ability content with enough detail for the final section.
                """;

        var result = service.chunkMarkdown("Wraith", markdown);

        assertTrue(result.stream().anyMatch(item -> item.getHeadingPath().equals("Wraith > Abilities > Into the Void")));
        assertTrue(result.stream().anyMatch(item -> item.getHeadingPath().equals("Wraith > Abilities > Voices from the Void")));
        assertTrue(result.stream().anyMatch(item -> item.getHeadingPath().equals("Wraith > Abilities > Dimensional Rift")));
        assertFalse(result.stream().anyMatch(item -> item.getHeadingPath().contains(
                "Into the Void > Voices from the Void")));
    }

    @Test
    void shouldIncludeDifferentDocumentH1InHeadingPath() {
        KnowledgeChunkingService service = service(10, 60, 100, 0);

        var result = service.chunkMarkdown("Imported card", "# Canonical title\n## Abilities\nUseful content");

        assertEquals("Imported card > Canonical title > Abilities", result.get(0).getHeadingPath());
    }

    @Test
    void shouldUseTargetLengthMergeSmallTailAndStayWithinMaxAfterOverlap() {
        KnowledgeChunkingService service = service(40, 80, 100, 20);
        String text = "A sentence with enough words to create semantic boundaries. ".repeat(12);

        var result = service.chunkMarkdown("Length test", text);

        assertTrue(result.size() > 2);
        assertTrue(result.stream().allMatch(item -> item.getContentText().length() <= 100));
        assertTrue(result.subList(0, result.size() - 1).stream()
                .allMatch(item -> item.getContentText().length() >= 40));
    }

    @Test
    void shouldRepairHighConfidenceWikiLinkBoundaries() {
        KnowledgeChunkingService service = service(10, 200, 300, 0);
        String markdown = """
                # Wraith
                ## Abilities
                ### Into the Void
                Wraithis aSkirmisher. Her tactical abilityInto the Voidallows safe repositioning.
                """;

        var result = service.chunkMarkdown("Wraith", markdown);
        String content = result.get(0).getContentText();

        assertTrue(content.contains("Wraith is"));
        assertTrue(content.contains("a Skirmisher"));
        assertTrue(content.contains("ability Into the Void allows"));
    }

    @Test
    void shouldNotCreateHeadingOnlyChunk() {
        KnowledgeChunkingService service = service(10, 80, 120, 0);
        String markdown = "# Wraith\n## Abilities\n### Dimensional Rift\nPortal gameplay details.";

        var result = service.chunkMarkdown("Wraith", markdown);

        assertEquals(1, result.size());
        assertEquals("Portal gameplay details.", result.get(0).getContentText());
    }

    private KnowledgeChunkingService service(int min, int target, int max, int overlap) {
        RagProperties properties = new RagProperties();
        properties.setChunkMinChars(min);
        properties.setChunkTargetChars(target);
        properties.setChunkMaxChars(max);
        properties.setChunkOverlapChars(overlap);
        return new KnowledgeChunkingService(properties);
    }
}
