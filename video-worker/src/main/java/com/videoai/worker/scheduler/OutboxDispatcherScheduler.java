package com.videoai.worker.scheduler;

import com.videoai.common.domain.TaskOutbox;
import com.videoai.common.message.TaskMessage;
import com.videoai.common.utils.RetryBackoffUtil;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.mysql.mapper.TaskOutboxMapper;
import com.videoai.infra.service.TaskOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 投递调度器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcherScheduler {

    private final TaskOutboxMapper taskOutboxMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final TaskOutboxService taskOutboxService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    @Qualifier("outboxCallbackExecutor")
    private final Executor outboxCallbackExecutor;

    @Value("${videoai.outbox.dispatch-batch-size:50}")
    private int dispatchBatchSize;

    @Value("${videoai.outbox.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    @Scheduled(fixedDelayString = "${videoai.outbox.dispatch-interval-ms:3000}")
    public void dispatch() {
        List<TaskOutbox> outboxes = taskOutboxMapper.selectReadyToDispatch(dispatchBatchSize);
        if (outboxes.isEmpty()) {
            return;
        }

        for (TaskOutbox outbox : outboxes) {
            dispatchAsync(outbox);
        }
    }

    private void dispatchAsync(TaskOutbox outbox) {
        try {
            if (taskOutboxMapper.markSending(outbox.getId()) == 0) {
                return;
            }

            TaskMessage message = taskOutboxService.parsePayload(outbox.getPayload());
            message.setDispatchedAt(System.currentTimeMillis());
            kafkaTemplate.send(TopicConstant.TASK_TOPIC, outbox.getTaskId(), message)
                    .orTimeout(sendTimeoutMs, TimeUnit.MILLISECONDS)
                    .whenCompleteAsync((result, error) -> completeDispatch(outbox, error),
                            outboxCallbackExecutor);
        } catch (Exception e) {
            markFailed(outbox, e);
        }
    }

    private void completeDispatch(TaskOutbox outbox, Throwable error) {
        try {
            if (error == null) {
                int sentRows = taskOutboxMapper.markSent(outbox.getId());
                if (sentRows == 1) {
                    // Consumer 可能已经把 PENDING 推进为 PROCESSING，此时更新 0 行是正常竞态。
                    analysisTaskMapper.markQueued(outbox.getTaskId(), outbox.getBusinessRetryNo());
                } else {
                    log.warn("Outbox ACK received but row is no longer SENDING: id={}, taskId={}",
                            outbox.getId(), outbox.getTaskId());
                }
            } else {
                markFailed(outbox, unwrap(error));
            }
        } catch (Exception callbackError) {
            // 保持 SENDING，由恢复调度器重新置为 NEW，维持至少一次投递语义。
            log.error("Outbox completion callback failed: id={}, taskId={}",
                    outbox.getId(), outbox.getTaskId(), callbackError);
        }
    }

    private void markFailed(TaskOutbox outbox, Throwable error) {
        int nextAttempt = (outbox.getSendAttemptCount() == null ? 0 : outbox.getSendAttemptCount()) + 1;
        taskOutboxMapper.markSendFailed(outbox.getId(),
                truncate(error.getMessage()),
                RetryBackoffUtil.nextDispatchRetryAt(nextAttempt));
        log.warn("Outbox dispatch failed: id={}, taskId={}", outbox.getId(), outbox.getTaskId(), error);
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
