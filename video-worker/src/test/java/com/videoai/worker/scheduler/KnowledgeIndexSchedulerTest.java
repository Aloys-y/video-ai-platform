package com.videoai.worker.scheduler;

import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.rag.service.KnowledgeIndexJobService;
import com.videoai.rag.service.KnowledgeIndexingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexSchedulerTest {

    @Mock
    private KnowledgeIndexJobService knowledgeIndexJobService;
    @Mock
    private KnowledgeIndexingService knowledgeIndexingService;

    @Test
    void shouldProcessJobsScannedFromDatabase() {
        KnowledgeIndexScheduler scheduler = new KnowledgeIndexScheduler(
                knowledgeIndexJobService, knowledgeIndexingService);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId("job-1");
        when(knowledgeIndexJobService.selectReadyToProcess()).thenReturn(List.of(job));

        scheduler.processReadyJobs();

        verify(knowledgeIndexJobService).failStaleProcessingJobs();
        verify(knowledgeIndexingService).processJob("job-1");
    }

    @Test
    void shouldMarkFailedWhenJobCannotEnterProcessor() {
        KnowledgeIndexScheduler scheduler = new KnowledgeIndexScheduler(
                knowledgeIndexJobService, knowledgeIndexingService);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId("job-2");
        when(knowledgeIndexJobService.selectReadyToProcess()).thenReturn(List.of(job));
        org.mockito.Mockito.doThrow(new IllegalStateException("job disappeared"))
                .when(knowledgeIndexingService).processJob("job-2");

        scheduler.processReadyJobs();

        verify(knowledgeIndexJobService).markFailed("job-2", "job disappeared");
    }
}
