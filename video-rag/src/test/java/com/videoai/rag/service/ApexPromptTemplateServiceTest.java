package com.videoai.rag.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApexPromptTemplateServiceTest {

    @Test
    void shouldProvideDefaultPromptAndKnowledgeBlock() {
        ApexPromptTemplateService service = new ApexPromptTemplateService();

        assertEquals("请分析这段 Apex 游戏视频。", service.normalizeUserPrompt(" "));
        assertTrue(service.systemPrompt().contains("## 对局总览"));
        assertTrue(service.retrievalBlock("命中片段").contains("命中片段"));
    }
}
