package com.videoai.worker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.utils.RetryBackoffUtil;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.service.TaskOutboxService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 任务失败与重试编排服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRetryService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final TaskOutboxService taskOutboxService;
    private final TransactionTemplate transactionTemplate;

    public FailureResult handleExecutionFailure(String taskId, int businessRetryNo,
                                                String errorMessage, boolean retryable) {
        return transitionFromProcessing(taskId, businessRetryNo, errorMessage, retryable);
    }

    public FailureResult recoverTimedOutTask(String taskId, int businessRetryNo) {
        return transitionFromProcessing(taskId, businessRetryNo,
                "Task processing timeout", true);
    }

    private FailureResult transitionFromProcessing(String taskId, int businessRetryNo,
                                                   String errorMessage, boolean retryable) {
        return transactionTemplate.execute(status -> {
            AnalysisTask task = queryByTaskId(taskId);
            if (task == null || task.isFinalState()) {
                return FailureResult.stale();
            }

            int currentRetryCount = normalizeRetryCount(task.getRetryCount());
            if (task.getStatusEnum() != TaskStatus.PROCESSING || currentRetryCount != businessRetryNo) {
                return FailureResult.stale();
            }

            if (retryable && task.canRetry()) {
                int nextRetryNo = currentRetryCount + 1;
                LocalDateTime nextRetryAt = RetryBackoffUtil.nextBusinessRetryAt(nextRetryNo);
                int rows = analysisTaskMapper.scheduleRetry(taskId, currentRetryCount, nextRetryAt, errorMessage);
                if (rows == 0) {
                    AnalysisTask latest = queryByTaskId(taskId);
                    if (latest == null || latest.isFinalState()
                            || latest.getStatusEnum() != TaskStatus.PROCESSING
                            || normalizeRetryCount(latest.getRetryCount()) != businessRetryNo) {
                        return FailureResult.stale();
                    }
                    throw new IllegalStateException("Failed to schedule retry for task: " + taskId);
                }
                taskOutboxService.createExecuteOutbox(task, nextRetryNo, nextRetryAt);
                return FailureResult.retryScheduled(nextRetryNo, nextRetryAt);
            }

            int rows = analysisTaskMapper.markAsDead(taskId, currentRetryCount, errorMessage);
            if (rows == 0) {
                AnalysisTask latest = queryByTaskId(taskId);
                if (latest == null || latest.isFinalState()
                        || latest.getStatusEnum() != TaskStatus.PROCESSING
                        || normalizeRetryCount(latest.getRetryCount()) != businessRetryNo) {
                    return FailureResult.stale();
                }
                throw new IllegalStateException("Failed to mark task dead: " + taskId);
            }
            return FailureResult.dead();
        });
    }

    private AnalysisTask queryByTaskId(String taskId) {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getTaskId, taskId);
        return analysisTaskMapper.selectOne(wrapper);
    }

    private int normalizeRetryCount(Integer retryCount) {
        return retryCount == null ? 0 : retryCount;
    }

    @Getter
    public static final class FailureResult {
        private final Outcome outcome;
        private final Integer nextRetryNo;
        private final LocalDateTime nextRetryAt;

        private FailureResult(Outcome outcome, Integer nextRetryNo, LocalDateTime nextRetryAt) {
            this.outcome = outcome;
            this.nextRetryNo = nextRetryNo;
            this.nextRetryAt = nextRetryAt;
        }

        public static FailureResult retryScheduled(Integer nextRetryNo, LocalDateTime nextRetryAt) {
            return new FailureResult(Outcome.RETRY_SCHEDULED, nextRetryNo, nextRetryAt);
        }

        public static FailureResult dead() {
            return new FailureResult(Outcome.DEAD, null, null);
        }

        public static FailureResult stale() {
            return new FailureResult(Outcome.STALE, null, null);
        }
    }

    public enum Outcome {
        RETRY_SCHEDULED,
        DEAD,
        STALE
    }
}
