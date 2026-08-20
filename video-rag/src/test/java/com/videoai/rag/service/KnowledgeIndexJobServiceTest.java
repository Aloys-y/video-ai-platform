package com.videoai.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.infra.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexJobServiceTest {

    @Mock
    private com.videoai.infra.mysql.mapper.KnowledgeIndexJobMapper knowledgeIndexJobMapper;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void shouldWaitForKafkaAckBeforeReturning() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setDispatchSendTimeoutSeconds(1);
        KnowledgeIndexJobService service = new KnowledgeIndexJobService(
                knowledgeIndexJobMapper, kafkaTemplate, new ObjectMapper(), ragProperties);

        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId("job-1");
        job.setBaseCode("apex-default");
        job.setJobType("UPSERT_CARD");
        job.setCardCode("card-1");

        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(future);

        assertDoesNotThrow(() -> service.dispatch(job));
    }

    @Test
    void shouldThrowWhenKafkaSendFails() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setDispatchSendTimeoutSeconds(1);
        KnowledgeIndexJobService service = new KnowledgeIndexJobService(
                knowledgeIndexJobMapper, kafkaTemplate, new ObjectMapper(), ragProperties);

        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId("job-2");
        job.setBaseCode("apex-default");
        job.setJobType("REBUILD_ALL");

        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(future);

        assertThrows(IllegalStateException.class, () -> service.dispatch(job));
    }

    @Test
    void shouldRecoverQueuedAndTerminateTimedOutProcessingJobs() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setQueuedRecoveryTimeoutSeconds(60);
        ragProperties.setProcessingTimeoutSeconds(1800);
        ragProperties.setRebuildProcessingTimeoutSeconds(21600);
        KnowledgeIndexJobService service = new KnowledgeIndexJobService(
                knowledgeIndexJobMapper, kafkaTemplate, new ObjectMapper(), ragProperties);

        when(knowledgeIndexJobMapper.recoverStaleQueued(any())).thenReturn(2);
        when(knowledgeIndexJobMapper.failStaleProcessing(any(), any())).thenReturn(1);

        KnowledgeIndexJobService.RecoveryResult result = service.recoverStaleJobs();

        assertEquals(2, result.requeued());
        assertEquals(1, result.failed());
        verify(knowledgeIndexJobMapper).recoverStaleQueued(any());
        verify(knowledgeIndexJobMapper).failStaleProcessing(any(), any());
    }
}
