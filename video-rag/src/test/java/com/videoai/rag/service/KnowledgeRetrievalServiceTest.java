package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.rag.RagContext;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorSearchResult;
import com.videoai.infra.rag.vector.EmbeddingProvider;
import com.videoai.infra.rag.vector.VectorStoreClient;
import com.videoai.rag.model.RagRetrievalTrace;
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
import static org.mockito.Mockito.never;
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

    @Mock
    private Bm25RetrievalService bm25RetrievalService;

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
                new LegendQueryEnhancementService(properties), bm25RetrievalService);

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
    void shouldExposeCandidatesAtEveryRetrievalStage() {
        properties.setFinalTopK(2);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(
                        result("wraith_0", "wraith", 0.93),
                        result("wraith_1", "wraith", 0.90),
                        result("r301_0", "r301", 0.86),
                        result("low_0", "low", 0.60)));

        RagRetrievalTrace trace = service.retrieveTrace("恶灵如何配合 R-301？");

        assertEquals(4, trace.getRawCandidates().size());
        assertEquals(3, trace.getScorePassedCandidates().size());
        assertEquals(List.of("wraith", "r301"),
                trace.getDiversifiedCandidates().stream().map(hit -> hit.getCardCode()).toList());
        assertEquals(2, trace.getSelectedCandidates().size());
        assertEquals(trace.getSelectedCandidates(), trace.getContext().getHits());
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
    void shouldRestrictRetrievalToCuratedLegendCategoryWhenEnabled() {
        properties.setLegendPcGameplayFilterEnabled(true);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of());

        service.retrieve("Wraith abilities");

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreClient).search(eq(List.of(0.1F, 0.2F)), eq(12), filterCaptor.capture());
        String filter = filterCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(filter.contains("category == \"LEGEND\""));
        org.junit.jupiter.api.Assertions.assertFalse(filter.contains("card_code not in"));
    }

    @Test
    void shouldFuseDenseAndLexicalCandidatesWithRrfWhenHybridEnabled() {
        properties.setHybridRetrievalEnabled(true);
        properties.setMinScore(0.72);
        properties.setFinalTopK(2);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(
                        result("wraith_overview", "wraith", 0.90),
                        result("wraith_phase", "wraith", 0.80),
                        result("crypto_drone", "crypto", 0.74)));

        com.videoai.common.domain.KnowledgeChunk lexicalChunk = new com.videoai.common.domain.KnowledgeChunk();
        lexicalChunk.setVectorId("wraith_phase");
        lexicalChunk.setCardCode("wraith");
        lexicalChunk.setTitle("恶灵");
        lexicalChunk.setCategory("LEGEND");
        lexicalChunk.setHeadingPath("恶灵 > 技能 > 进入虚空");
        lexicalChunk.setContentText("进入虚空的技能说明");
        when(bm25RetrievalService.search(anyString(), eq("apex-default"), eq("v1"), eq(20)))
                .thenReturn(List.of(new com.videoai.rag.model.LexicalSearchResult(lexicalChunk, 8.5)));

        RagRetrievalTrace trace = service.retrieveTrace("恶灵进入虚空怎么用？");

        assertEquals("wraith_phase", trace.getFusedCandidates().get(0).getVectorId());
        assertEquals(1, trace.getFusedCandidates().get(0).getLexicalRank());
        assertEquals(3, trace.getRawCandidates().size());
        assertEquals(1, trace.getLexicalCandidates().size());
    }

    @Test
    void shouldKeepNoAnswerRejectionWhenBm25HasCandidatesButDenseGateFails() {
        properties.setHybridRetrievalEnabled(true);
        properties.setMinScore(0.72);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(result("weak", "wraith", 0.60)));

        com.videoai.common.domain.KnowledgeChunk lexicalChunk = new com.videoai.common.domain.KnowledgeChunk();
        lexicalChunk.setVectorId("lexical_only");
        lexicalChunk.setCardCode("wraith");
        lexicalChunk.setTitle("恶灵");
        lexicalChunk.setCategory("LEGEND");
        lexicalChunk.setHeadingPath("恶灵 > 技能");
        lexicalChunk.setContentText("词法命中但语义门控不通过");
        when(bm25RetrievalService.search(anyString(), eq("apex-default"), eq("v1"), eq(20)))
                .thenReturn(List.of(new com.videoai.rag.model.LexicalSearchResult(lexicalChunk, 9.0)));

        RagContext context = service.retrieve("范围外问题");

        assertEquals("MISS", context.getStatus());
        assertEquals(0, context.getHits().size());
    }

    @Test
    void shouldExcludeLexicalOnlyCandidateWhenHybridUnionIsDisabled() {
        properties.setHybridRetrievalEnabled(true);
        properties.setHybridLexicalUnionEnabled(false);
        properties.setMinScore(0.72);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(result("dense", "wraith", 0.80)));

        com.videoai.common.domain.KnowledgeChunk lexicalChunk = new com.videoai.common.domain.KnowledgeChunk();
        lexicalChunk.setVectorId("lexical_only");
        lexicalChunk.setCardCode("crypto");
        lexicalChunk.setTitle("密客");
        lexicalChunk.setCategory("LEGEND");
        lexicalChunk.setHeadingPath("密客 > 技能");
        lexicalChunk.setContentText("纯词法候选");
        when(bm25RetrievalService.search(anyString(), eq("apex-default"), eq("v1"), eq(20)))
                .thenReturn(List.of(new com.videoai.rag.model.LexicalSearchResult(lexicalChunk, 9.0)));

        RagRetrievalTrace trace = service.retrieveTrace("恶灵技能怎么用？");

        assertEquals(List.of("dense"),
                trace.getScorePassedCandidates().stream().map(hit -> hit.getVectorId()).toList());
    }

    @Test
    void shouldAppendBm25CandidatesWithoutReorderingDenseResultsWhenConditionalRescueIsNeeded() {
        properties.setHybridRetrievalEnabled(true);
        properties.setHybridConditionalRescueEnabled(true);
        properties.setFinalTopK(3);
        properties.setMaxChunksPerCard(1);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(
                        result("wraith_0", "wraith", 0.90),
                        result("wraith_1", "wraith", 0.85),
                        result("crypto_0", "crypto", 0.68),
                        result("caustic_0", "caustic", 0.65),
                        result("weak_0", "weak", 0.60)));
        when(bm25RetrievalService.search(anyString(), eq("apex-default"), eq("v1"), eq(20)))
                .thenReturn(List.of(
                        lexical("wraith_0", "wraith", 9.0),
                        lexical("crypto_0", "crypto", 8.0),
                        lexical("caustic_0", "caustic", 7.0)));

        RagRetrievalTrace trace = service.retrieveTrace("需要补充词法候选的问题");

        assertEquals(List.of("wraith_0", "crypto_0"),
                trace.getSelectedCandidates().stream().map(hit -> hit.getVectorId()).toList());
        assertEquals(0.90, trace.getSelectedCandidates().get(0).getDenseScore());
        assertEquals(3, trace.getLexicalCandidates().size());
    }

    @Test
    void shouldSkipBm25SearchWhenConditionalRescueHasEnoughDenseResults() {
        properties.setHybridRetrievalEnabled(true);
        properties.setHybridConditionalRescueEnabled(true);
        properties.setFinalTopK(3);
        properties.setMaxChunksPerCard(1);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(
                        result("wraith_0", "wraith", 0.90),
                        result("crypto_0", "crypto", 0.85),
                        result("caustic_0", "caustic", 0.80)));

        RagRetrievalTrace trace = service.retrieveTrace("纯向量已经足够的问题");

        assertEquals(List.of("wraith_0", "crypto_0", "caustic_0"),
                trace.getSelectedCandidates().stream().map(hit -> hit.getVectorId()).toList());
        assertEquals(0, trace.getLexicalCandidates().size());
        verify(bm25RetrievalService, never()).search(anyString(), anyString(), anyString(), eq(20));
    }

    @Test
    void shouldKeepMissAndSkipBm25WhenConditionalRescueDenseGateFails() {
        properties.setHybridRetrievalEnabled(true);
        properties.setHybridConditionalRescueEnabled(true);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(result("weak_0", "weak", 0.60)));

        RagContext context = service.retrieve("范围外问题");

        assertEquals("MISS", context.getStatus());
        verify(bm25RetrievalService, never()).search(anyString(), anyString(), anyString(), eq(20));
    }

    @Test
    void shouldRejectPureLexicalAndBelowFloorCandidatesDuringConditionalRescue() {
        properties.setHybridRetrievalEnabled(true);
        properties.setHybridConditionalRescueEnabled(true);
        properties.setFinalTopK(3);
        properties.setMaxChunksPerCard(1);
        properties.setHybridConditionalRescueMinDenseScore(0.60);
        when(vectorStoreClient.search(eq(List.of(0.1F, 0.2F)), eq(12), anyString()))
                .thenReturn(List.of(
                        result("wraith_0", "wraith", 0.90),
                        result("weak_0", "weak", 0.59)));
        when(bm25RetrievalService.search(anyString(), eq("apex-default"), eq("v1"), eq(20)))
                .thenReturn(List.of(
                        lexical("lexical_only", "crypto", 9.0),
                        lexical("weak_0", "weak", 8.0)));

        RagRetrievalTrace trace = service.retrieveTrace("词面很像但语义不足的问题");

        assertEquals(List.of("wraith_0"),
                trace.getSelectedCandidates().stream().map(hit -> hit.getVectorId()).toList());
    }

    private com.videoai.rag.model.LexicalSearchResult lexical(
            String vectorId, String cardCode, double score) {
        com.videoai.common.domain.KnowledgeChunk chunk = new com.videoai.common.domain.KnowledgeChunk();
        chunk.setVectorId(vectorId);
        chunk.setCardCode(cardCode);
        chunk.setTitle(cardCode);
        chunk.setCategory("LEGEND");
        chunk.setHeadingPath(cardCode + " > section");
        chunk.setContentText("knowledge for " + cardCode);
        return new com.videoai.rag.model.LexicalSearchResult(chunk, score);
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
