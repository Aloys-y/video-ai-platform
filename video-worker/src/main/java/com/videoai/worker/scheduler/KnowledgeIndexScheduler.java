package com.videoai.worker.scheduler;

import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.rag.service.KnowledgeIndexJobService;
import com.videoai.rag.service.KnowledgeIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexScheduler {

    private final KnowledgeIndexJobService knowledgeIndexJobService;
    private final KnowledgeIndexingService knowledgeIndexingService;

    @Scheduled(fixedDelayString = "${videoai.rag.scan-interval-ms:3000}")
    public void processReadyJobs() {
        int failed = knowledgeIndexJobService.failStaleProcessingJobs();
        if (failed > 0) {
            log.warn("Terminated stale knowledge index jobs: failed={}", failed);
        }

        List<KnowledgeIndexJob> jobs = knowledgeIndexJobService.selectReadyToProcess();
        if (jobs.isEmpty()) {
            return;
        }
        for (KnowledgeIndexJob job : jobs) {
            try {
                // processJob 内部用 NEW/QUEUED -> PROCESSING 条件更新原子抢占，多 Worker 不会重复执行。
                knowledgeIndexingService.processJob(job.getJobId());
            } catch (Exception e) {
                log.error("Knowledge index scan failed before job processing: jobId={}", job.getJobId(), e);
                knowledgeIndexJobService.markFailed(job.getJobId(), e.getMessage());
            }
        }
    }
}
