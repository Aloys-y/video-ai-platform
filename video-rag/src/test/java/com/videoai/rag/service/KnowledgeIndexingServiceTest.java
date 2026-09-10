package com.videoai.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.common.domain.KnowledgeChunk;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.common.enums.KnowledgeIndexJobType;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorRecord;
import com.videoai.rag.model.ChunkedSegment;
import com.videoai.infra.rag.vector.EmbeddingProvider;
import com.videoai.infra.rag.vector.VectorStoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexingServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeCardMapper knowledgeCardMapper;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private KnowledgeIndexJobService knowledgeIndexJobService;

    @Mock
    private KnowledgeChunkingService knowledgeChunkingService;

    private final RagProperties ragProperties = new RagProperties();

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private VectorStoreClient vectorStoreClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Test
    void rebuildShouldStillCleanDisabledCards() {
        KnowledgeIndexingService service = new KnowledgeIndexingService(
                knowledgeBaseService,
                knowledgeCardMapper,
                knowledgeChunkMapper,
                knowledgeIndexJobService,
                knowledgeChunkingService,
                ragProperties,
                embeddingProvider,
                vectorStoreClient,
                new ObjectMapper(),
                transactionTemplate);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId("job-1");
        job.setJobType(KnowledgeIndexJobType.REBUILD_ALL.getCode());

        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode("apex-default");
        base.setCurrentVersionTag("v1");

        KnowledgeCard disabledCard = new KnowledgeCard();
        disabledCard.setBaseCode("apex-default");
        disabledCard.setCardCode("card-disabled");
        disabledCard.setEnabled(0);
        disabledCard.setTimeless(0);
        disabledCard.setVersionTag("v1");

        KnowledgeChunk oldChunk = new KnowledgeChunk();
        oldChunk.setVectorId("card-disabled_1");

        when(knowledgeIndexJobService.getRequiredJob("job-1")).thenReturn(job);
        when(knowledgeIndexJobService.markProcessing("job-1")).thenReturn(true);
        when(knowledgeBaseService.getRequiredBase()).thenReturn(base);
        when(knowledgeCardMapper.selectRebuildTargets("apex-default", "v1"))
                .thenReturn(List.of(disabledCard));
        when(knowledgeCardMapper.selectByCardCode("apex-default", "card-disabled"))
                .thenReturn(disabledCard);
        when(knowledgeChunkMapper.selectByCardCode("apex-default", "card-disabled"))
                .thenReturn(List.of(oldChunk));

        service.processJob("job-1");

        verify(knowledgeCardMapper).selectRebuildTargets("apex-default", "v1");
        verify(knowledgeCardMapper, never()).selectRetrievalCandidates(anyString(), anyString());
        verify(vectorStoreClient).deleteByIds(List.of("card-disabled_1"));
        verify(knowledgeChunkMapper).deleteByCardCode("apex-default", "card-disabled");
        verify(knowledgeCardMapper).updateIndexState(
                eq("apex-default"),
                eq("card-disabled"),
                anyString(),
                anyString(),
                any(),
                anyString());
        verify(knowledgeIndexJobService).markSuccess("job-1", 0, 0, 0);
        verify(knowledgeIndexJobService, never()).markFailed(anyString(), anyString());
        verify(knowledgeChunkingService, never()).chunkMarkdown(anyString(), anyString());
        verify(embeddingProvider, never()).embedDocument(anyString());
    }

    @Test
    void shouldExcludeHeadingPathFromEmbeddingButKeepItInMetadataByDefault() {
        KnowledgeIndexingService service = newService();
        KnowledgeCard card = card();
        ChunkedSegment segment = segment();
        when(embeddingProvider.embedDocument(anyString())).thenReturn(List.of(0.1F));

        List<VectorRecord> records = service.buildVectorRecords(card, List.of(segment));

        org.mockito.ArgumentCaptor<String> textCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(embeddingProvider).embedDocument(textCaptor.capture());
        assertFalse(textCaptor.getValue().contains("Section:"));
        assertTrue(textCaptor.getValue().contains("Content: 战术技能说明"));
        assertEquals("恶灵 > 技能", records.get(0).getFields().get("heading_path"));
    }

    @Test
    void shouldIncludeHeadingPathInEmbeddingWhenExplicitlyEnabled() {
        ragProperties.setEmbeddingHeadingPathEnabled(true);
        KnowledgeIndexingService service = newService();
        when(embeddingProvider.embedDocument(anyString())).thenReturn(List.of(0.1F));

        service.buildVectorRecords(card(), List.of(segment()));

        org.mockito.ArgumentCaptor<String> textCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(embeddingProvider).embedDocument(textCaptor.capture());
        assertTrue(textCaptor.getValue().contains("Section: 恶灵 > 技能"));
    }

    private KnowledgeIndexingService newService() {
        return new KnowledgeIndexingService(
                knowledgeBaseService, knowledgeCardMapper, knowledgeChunkMapper,
                knowledgeIndexJobService, knowledgeChunkingService, ragProperties,
                embeddingProvider, vectorStoreClient, new ObjectMapper(), transactionTemplate);
    }

    private KnowledgeCard card() {
        KnowledgeCard card = new KnowledgeCard();
        card.setBaseCode("apex-default");
        card.setVersionTag("v1");
        card.setCardCode("wraith");
        card.setTitle("恶灵");
        card.setAliases("Wraith");
        card.setCategory("LEGEND");
        card.setSubjectCode("wraith");
        card.setEnabled(1);
        return card;
    }

    private ChunkedSegment segment() {
        return ChunkedSegment.builder()
                .chunkNo(0)
                .headingPath("恶灵 > 技能")
                .contentText("战术技能说明")
                .build();
    }
}
