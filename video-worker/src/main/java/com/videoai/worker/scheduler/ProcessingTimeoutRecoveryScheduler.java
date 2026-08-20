package com.videoai.worker.scheduler;

import com.videoai.common.domain.AnalysisTask;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.worker.service.TaskRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理超时任务恢复
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingTimeoutRecoveryScheduler {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final TaskRetryService taskRetryService;

    @Value("${videoai.worker.task-timeout-minutes:30}")
    private int taskTimeoutMinutes;

    @Value("${videoai.worker.timeout-recovery-batch-size:20}")
    private int recoveryBatchSize;

    @Scheduled(fixedDelayString = "${videoai.worker.timeout-recovery-interval-ms:60000}")
    public void recover() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(taskTimeoutMinutes);
        List<AnalysisTask> tasks = analysisTaskMapper.selectTimedOutProcessingTasks(deadline, recoveryBatchSize);
        for (AnalysisTask task : tasks) {
            int retryNo = task.getRetryCount() == null ? 0 : task.getRetryCount();
            TaskRetryService.FailureResult result = taskRetryService.recoverTimedOutTask(task.getTaskId(), retryNo);
            if (result.getOutcome() != TaskRetryService.Outcome.STALE) {
                log.warn("Recovered timed out task: taskId={}, outcome={}",
                        task.getTaskId(), result.getOutcome());
            }
        }
    }
}
