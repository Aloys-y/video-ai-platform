package com.videoai.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.common.domain.KnowledgeChunk;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.common.enums.KnowledgeIndexJobType;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
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
}
