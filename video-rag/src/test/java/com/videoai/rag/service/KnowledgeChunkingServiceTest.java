package com.videoai.rag.service;

import com.videoai.infra.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        properties.setChunkMaxChars(120);
        properties.setChunkOverlapChars(20);

        KnowledgeChunkingService service = new KnowledgeChunkingService(properties);
        // 超长无标题文本，会被迫切分成多块
        String longText = "这是一段用于测试分块逻辑的较长文本内容，用来验证 overlap 是否生效。".repeat(15);

        var result = service.chunkMarkdown("测试卡片", longText);

        assertTrue(result.size() >= 2, "应该切成多块，实际 " + result.size());

        // 相邻 chunk 有 overlap：后一个 chunk 开头包含前一个 chunk 的末尾
        for (int i = 1; i < result.size(); i++) {
            String prev = result.get(i - 1).getContentText();
            String curr = result.get(i).getContentText();
            assertTrue(prev.length() >= 20, "前一个 chunk 应该足够长");
            String prevTail = prev.substring(prev.length() - 20);
            assertTrue(curr.startsWith(prevTail),
                    "chunk " + i + " 开头应包含前一个 chunk 的末尾（overlap）");
        }
    }
}
