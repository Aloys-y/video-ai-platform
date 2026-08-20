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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
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

    @Value("${videoai.outbox.dispatch-batch-size:50}")
    private int dispatchBatchSize;

    @Scheduled(fixedDelayString = "${videoai.outbox.dispatch-interval-ms:3000}")
    public void dispatch() {
        List<TaskOutbox> outboxes = taskOutboxMapper.selectReadyToDispatch(dispatchBatchSize);
        if (outboxes.isEmpty()) {
            return;
        }

        for (TaskOutbox outbox : outboxes) {
            dispatchSingle(outbox);
        }
    }

    private void dispatchSingle(TaskOutbox outbox) {
        if (taskOutboxMapper.markSending(outbox.getId()) == 0) {
            return;
        }

        try {
            TaskMessage message = taskOutboxService.parsePayload(outbox.getPayload());
            message.setDispatchedAt(System.currentTimeMillis());
            kafkaTemplate.send(TopicConstant.TASK_TOPIC, outbox.getTaskId(), message)
                    .get(10, TimeUnit.SECONDS);
            taskOutboxMapper.markSent(outbox.getId());
            analysisTaskMapper.markQueued(outbox.getTaskId(), outbox.getBusinessRetryNo());
        } catch (Exception e) {
            int nextAttempt = (outbox.getSendAttemptCount() == null ? 0 : outbox.getSendAttemptCount()) + 1;
            taskOutboxMapper.markSendFailed(outbox.getId(),
                    truncate(e.getMessage()),
                    RetryBackoffUtil.nextDispatchRetryAt(nextAttempt));
            log.warn("Outbox dispatch failed: id={}, taskId={}", outbox.getId(), outbox.getTaskId(), e);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
