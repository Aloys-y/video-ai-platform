package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.rag.RagContext;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorSearchResult;
import com.videoai.infra.rag.vector.EmbeddingProvider;
import com.videoai.infra.rag.vector.VectorStoreClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private VectorStoreClient vectorStoreClient;

    private RagProperties properties;
    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setTopK(12);
        properties.setFinalTopK(6);
        properties.setMaxChunksPerCard(1);
        properties.setMinScore(0.72);
        properties.setMaxContextChars(3500);
        properties.setLegendPcGameplayFilterEnabled(false);
        service = new KnowledgeRetrievalService(
                knowledgeBaseService, embeddingProvider, vectorStoreClient, properties,
                new LegendQueryEnhancementService(properties));

        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode("apex-default");
        base.setCurrentVersionTag("v1");
        when(knowledgeBaseService.getRequiredBase()).thenReturn(base);
        when(embeddingProvider.embedQuery(anyString())).thenReturn(List.of(0.1F, 0.2F));
    }

    @Test
    void shouldUseQueryEmbeddingAndDiversifyCards() {
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(
                        result("wraith_0", "wraith", 0.93),
                        result("wraith_1", "wraith", 0.90),
                        result("r301_0", "r301", 0.86),
                        result("low_0", "low", 0.60)));

        RagContext context = service.retrieve("分析恶灵使用 R301 的团战");

        assertEquals("HIT", context.getStatus());
        assertEquals(List.of("wraith", "r301"),
                context.getHits().stream().map(hit -> hit.getCardCode()).toList());
        verify(embeddingProvider).embedQuery(anyString());
    }

    @Test
    void shouldReturnMissWhenNoResultPassesThreshold() {
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(result("low_0", "low", 0.50)));

        RagContext context = service.retrieve("未知内容");

        assertEquals("MISS", context.getStatus());
        assertEquals(0, context.getHits().size());
        assertEquals("", context.getContextText());
    }

    @Test
    void shouldAllowGenericQueryExpansionToBeDisabledForAblation() {
        properties.setQueryExpansionEnabled(false);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of());

        RagContext context = service.retrieve("  R-301 使用什么弹药？  ");

        assertEquals("R-301 使用什么弹药？", context.getQueryText());
        verify(embeddingProvider).embedQuery("R-301 使用什么弹药？");
    }

    @Test
    void shouldFilterMobileLoreAndAuxiliaryLegendCardsWhenEnabled() {
        properties.setLegendPcGameplayFilterEnabled(true);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of());

        service.retrieve("Wraith abilities");

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreClient).search(eq(List.of(0.1F, 0.2F)), eq(12), filterCaptor.capture());
        String filter = filterCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(filter.contains("category == \"LEGEND\""));
        org.junit.jupiter.api.Assertions.assertTrue(filter.contains("card_code not in"));
        org.junit.jupiter.api.Assertions.assertTrue(filter.contains("\"wraith-mobile\""));
        org.junit.jupiter.api.Assertions.assertTrue(filter.contains("\"wraith-id\""));
        org.junit.jupiter.api.Assertions.assertTrue(filter.contains("\"ballistic-character\""));
    }

    private VectorSearchResult result(String id, String cardCode, double score) {
        return VectorSearchResult.builder()
                .id(id)
                .score(score)
                .fields(Map.of(
                        "card_code", cardCode,
                        "title", cardCode,
                        "category", "MECHANIC",
                        "heading_path", cardCode + " > section",
                        "content_text", "knowledge for " + cardCode))
                .build();
    }
}
