package com.videoai.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.infra.mysql.mapper.KnowledgeIndexJobMapper;
import com.videoai.infra.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexJobServiceTest {

    @Mock
    private KnowledgeIndexJobMapper knowledgeIndexJobMapper;

    @Test
    void shouldScanReadyJobsUsingConfiguredBatchSize() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setScanBatchSize(7);
        KnowledgeIndexJobService service = new KnowledgeIndexJobService(
                knowledgeIndexJobMapper, new ObjectMapper(), ragProperties);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId("job-1");
        when(knowledgeIndexJobMapper.selectReadyToProcess(7)).thenReturn(List.of(job));

        List<KnowledgeIndexJob> result = service.selectReadyToProcess();

        assertEquals(List.of(job), result);
        verify(knowledgeIndexJobMapper).selectReadyToProcess(7);
    }

    @Test
    void shouldTerminateTimedOutProcessingJobs() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setProcessingTimeoutSeconds(1800);
        ragProperties.setRebuildProcessingTimeoutSeconds(21600);
        KnowledgeIndexJobService service = new KnowledgeIndexJobService(
                knowledgeIndexJobMapper, new ObjectMapper(), ragProperties);
        when(knowledgeIndexJobMapper.failStaleProcessing(any(), any())).thenReturn(1);

        int failed = service.failStaleProcessingJobs();

        assertEquals(1, failed);
        verify(knowledgeIndexJobMapper).failStaleProcessing(any(), any());
    }
}
