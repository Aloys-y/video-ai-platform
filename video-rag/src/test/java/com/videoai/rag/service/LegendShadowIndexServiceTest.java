package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.rag.config.MilvusProperties;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorRecord;
import com.videoai.infra.rag.vector.VectorStoreClient;
import com.videoai.rag.model.ChunkedSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegendShadowIndexServiceTest {

    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private KnowledgeCardMapper knowledgeCardMapper;
    @Mock private KnowledgeChunkingService knowledgeChunkingService;
    @Mock private KnowledgeIndexingService knowledgeIndexingService;
    @Mock private VectorStoreClient vectorStoreClient;

    @Test
    void shouldBuildOnlyIncludedCardsWithoutWritingMysqlChunks() {
        RagProperties rag = new RagProperties();
        rag.setShadowIndexBuildEnabled(true);
        rag.setChunkMinChars(400);
        rag.setChunkMaxChars(800);
        MilvusProperties milvus = new MilvusProperties();
        milvus.setCollection("apex_knowledge_chunk_l03_test");
        LegendShadowIndexService service = new LegendShadowIndexService(
                knowledgeBaseService, knowledgeCardMapper, knowledgeChunkingService,
                knowledgeIndexingService, vectorStoreClient, rag, milvus);

        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode("apex-default");
        KnowledgeCard wraith = card("wraith", "Wraith");
        KnowledgeCard mobile = card("wraith-mobile", "Wraith (Mobile)");
        when(knowledgeBaseService.getRequiredBase()).thenReturn(base);
        when(knowledgeCardMapper.selectByCategory("apex-default", "LEGEND"))
                .thenReturn(List.of(wraith, mobile));
        ChunkedSegment segment = ChunkedSegment.builder()
                .chunkNo(0).title("Wraith").headingPath("Wraith > Abilities")
                .contentText("x".repeat(500)).build();
        when(knowledgeChunkingService.chunkMarkdown("Wraith", "content"))
                .thenReturn(List.of(segment));
        VectorRecord record = VectorRecord.builder()
                .id("wraith_0").vector(List.of(0.1F)).fields(Map.of()).build();
        when(knowledgeIndexingService.buildVectorRecords(wraith, List.of(segment)))
                .thenReturn(List.of(record));

        Map<String, Object> result = service.build();

        verify(vectorStoreClient).ensureCollection();
        verify(vectorStoreClient).upsert(List.of(record));
        verify(knowledgeChunkingService, never()).chunkMarkdown("Wraith (Mobile)", "content");
        assertEquals(1, result.get("cardCount"));
        assertEquals(1, result.get("vectorCount"));
        assertEquals(500, result.get("p50Chars"));
        assertEquals(0L, result.get("belowMinCount"));
    }

    @Test
    void shouldReuseExistingShadowVectorsWhenPromotingMetadata() {
        RagProperties rag = new RagProperties();
        rag.setShadowIndexBuildEnabled(true);
        MilvusProperties milvus = new MilvusProperties();
        milvus.setCollection("apex_knowledge_chunk_l03_test");
        LegendShadowIndexService service = new LegendShadowIndexService(
                knowledgeBaseService, knowledgeCardMapper, knowledgeChunkingService,
                knowledgeIndexingService, vectorStoreClient, rag, milvus);

        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode("apex-default");
        KnowledgeCard wraith = card("wraith", "Wraith");
        when(knowledgeBaseService.getRequiredBase()).thenReturn(base);
        when(knowledgeCardMapper.selectByCategory("apex-default", "LEGEND"))
                .thenReturn(List.of(wraith));
        ChunkedSegment segment = ChunkedSegment.builder()
                .chunkNo(0).title("Wraith").headingPath("Wraith > Abilities")
                .contentText("content").build();
        VectorRecord metadata = VectorRecord.builder()
                .id("wraith_0").fields(Map.of()).build();
        when(vectorStoreClient.countByCard(
                "kb_code == \"apex-default\" and category == \"LEGEND\""))
                .thenReturn(Map.of("wraith", 1L));
        when(knowledgeChunkingService.chunkMarkdown("Wraith", "content"))
                .thenReturn(List.of(segment));
        when(knowledgeIndexingService.buildMetadataRecords(wraith, List.of(segment)))
                .thenReturn(List.of(metadata));
        when(knowledgeIndexingService.persistToDatabase(
                org.mockito.ArgumentMatchers.eq("apex-default"),
                org.mockito.ArgumentMatchers.eq(wraith),
                org.mockito.ArgumentMatchers.eq(List.of(segment)),
                org.mockito.ArgumentMatchers.eq(List.of(metadata)),
                org.mockito.ArgumentMatchers.startsWith("l03-shadow-promote-"),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(1);

        Map<String, Object> result = service.promote();

        verify(knowledgeIndexingService, never()).buildVectorRecords(wraith, List.of(segment));
        verify(vectorStoreClient, never()).upsert(org.mockito.ArgumentMatchers.anyList());
        assertEquals(1, result.get("persistedChunkCount"));
        assertEquals(0, result.get("newlyEmbeddedVectorCount"));
        assertEquals("apex_knowledge_chunk", result.get("baselineCollectionPreserved"));
    }

    private KnowledgeCard card(String code, String title) {
        KnowledgeCard card = new KnowledgeCard();
        card.setBaseCode("apex-default");
        card.setCardCode(code);
        card.setTitle(title);
        card.setContentMarkdown("content");
        card.setEnabled(1);
        return card;
    }
}
