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
}
