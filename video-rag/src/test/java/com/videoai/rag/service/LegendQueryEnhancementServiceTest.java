package com.videoai.rag.service;

import com.videoai.infra.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegendQueryEnhancementServiceTest {

    @Test
    void shouldResolvePlayerSlangAndAbilityWithoutGenericExpansion() {
        RagProperties properties = new RagProperties();
        properties.setLegendAliasEnhancementEnabled(true);
        LegendQueryEnhancementService service = new LegendQueryEnhancementService(properties);

        assertEquals(
                "胖胖什么时候交罩子？\n已解析英雄：直布罗陀。已解析技能：防护穹顶。",
                service.enhance("胖胖什么时候交罩子？"));
    }

    @Test
    void shouldResolveMultipleLegendsInStableOrder() {
        RagProperties properties = new RagProperties();
        properties.setLegendAliasEnhancementEnabled(true);
        LegendQueryEnhancementService service = new LegendQueryEnhancementService(properties);

        assertEquals(
                "狗子扫描和密客无人机有什么区别？\n已解析英雄：寻血猎犬, 密客。"
                        + "已解析技能：众父之眼, 侦查无人机。",
                service.enhance("狗子扫描和密客无人机有什么区别？"));
    }

    @Test
    void shouldLeaveUnknownOrDisabledQueryUnchanged() {
        RagProperties properties = new RagProperties();
        properties.setLegendAliasEnhancementEnabled(false);
        LegendQueryEnhancementService service = new LegendQueryEnhancementService(properties);

        assertEquals("胖胖什么时候交罩子？", service.enhance("胖胖什么时候交罩子？"));
        properties.setLegendAliasEnhancementEnabled(true);
        assertEquals("什么时候应该转点？", service.enhance("什么时候应该转点？"));
    }
}
