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
                "胖胖什么时候交罩子？\nResolved Apex Legend: Gibraltar. Resolved ability: Dome of Protection.",
                service.enhance("胖胖什么时候交罩子？"));
    }

    @Test
    void shouldResolveMultipleLegendsInStableOrder() {
        RagProperties properties = new RagProperties();
        properties.setLegendAliasEnhancementEnabled(true);
        LegendQueryEnhancementService service = new LegendQueryEnhancementService(properties);

        assertEquals(
                "狗子扫描和密客无人机有什么区别？\nResolved Apex Legend: Bloodhound, Crypto. "
                        + "Resolved ability: Eye of the Allfather, Surveillance Drone.",
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
