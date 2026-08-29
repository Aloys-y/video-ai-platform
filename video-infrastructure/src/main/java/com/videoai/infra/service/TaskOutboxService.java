package com.videoai.infra.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.domain.TaskOutbox;
import com.videoai.common.enums.OutboxEventType;
import com.videoai.common.enums.TaskOutboxStatus;
import com.videoai.common.message.TaskMessage;
import com.videoai.common.utils.IdGenerator;
import com.videoai.infra.mysql.mapper.TaskOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 任务Outbox服务
 */
@Service
@RequiredArgsConstructor
public class TaskOutboxService {

    private final TaskOutboxMapper taskOutboxMapper;
    private final ObjectMapper objectMapper;

    public TaskOutbox createExecuteOutbox(AnalysisTask task, int executionNo, LocalDateTime availableAt) {
        String eventId = IdGenerator.generateEventId();
        TaskMessage payload = TaskMessage.builder()
                .eventId(eventId)
                .taskId(task.getTaskId())
                .uploadId(task.getUploadId())
                .userId(task.getUserId())
                .videoUrl(task.getVideoUrl())
                .videoDuration(task.getVideoDuration())
                .businessRetryNo(executionNo)
                .timestamp(System.currentTimeMillis())
                .priority(5)
                .analysisType("FULL")
                .prompt(task.getPrompt())
                .build();

        TaskOutbox outbox = new TaskOutbox();
        outbox.setEventId(eventId);
        outbox.setTaskId(task.getTaskId());
        outbox.setEventType(OutboxEventType.TASK_EXECUTE.getCode());
        outbox.setBusinessRetryNo(executionNo);
        outbox.setPayload(writePayload(payload));
        outbox.setStatus(TaskOutboxStatus.NEW.getCode());
        outbox.setAvailableAt(availableAt);
        outbox.setSendAttemptCount(0);
        taskOutboxMapper.insert(outbox);
        return outbox;
    }

    public TaskMessage parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, TaskMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize task outbox payload", e);
        }
    }

    private String writePayload(TaskMessage payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task outbox payload", e);
        }
    }
}
