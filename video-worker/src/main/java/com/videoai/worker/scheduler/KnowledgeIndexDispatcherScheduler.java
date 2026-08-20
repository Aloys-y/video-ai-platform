package com.videoai.worker.scheduler;

import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.rag.service.KnowledgeIndexJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexDispatcherScheduler {

    private final KnowledgeIndexJobService knowledgeIndexJobService;

    @Scheduled(fixedDelayString = "${videoai.rag.dispatch-interval-ms:3000}")
    public void dispatch() {
        KnowledgeIndexJobService.RecoveryResult recovery = knowledgeIndexJobService.recoverStaleJobs();
        if (recovery.requeued() > 0 || recovery.failed() > 0) {
            log.warn("Recovered stale knowledge jobs: requeued={}, failed={}",
                    recovery.requeued(), recovery.failed());
        }

        List<KnowledgeIndexJob> jobs = knowledgeIndexJobService.selectReadyToDispatch();
        if (jobs.isEmpty()) {
            return;
        }
        for (KnowledgeIndexJob job : jobs) {
            if (!knowledgeIndexJobService.markQueued(job.getId())) {
                continue;
            }
            try {
                knowledgeIndexJobService.dispatch(job);
            } catch (Exception e) {
                log.error("Knowledge index dispatch failed: jobId={}", job.getJobId(), e);
                knowledgeIndexJobService.markFailed(job.getJobId(), e.getMessage());
            }
        }
    }
}
