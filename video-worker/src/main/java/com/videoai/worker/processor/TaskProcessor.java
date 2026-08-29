package com.videoai.worker.processor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.message.TaskMessage;
import com.videoai.common.rag.PromptEnvelope;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.redis.key.RedisKey;
import com.videoai.rag.service.RagOrchestrator;
import com.videoai.worker.service.AiService;
import com.videoai.worker.service.TaskFailureService;
import com.videoai.worker.service.provider.AiProviderException;
import com.videoai.worker.service.provider.AiVideoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 任务处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProcessor {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AiService aiService;
    private final StorageService storageService;
    private final AiVideoProvider aiVideoProvider;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TaskFailureService taskFailureService;
    private final RagOrchestrator ragOrchestrator;

    public boolean process(TaskMessage message) {
        String taskId = message.getTaskId();
        int executionNo = normalizeExecutionNo(message.getBusinessRetryNo());
        log.info("Processing task: {}, executionNo: {}", taskId, executionNo);

        AnalysisTask task = queryByTaskId(taskId);
        if (task == null) {
            log.warn("Task not found: {}", taskId);
            return true;
        }

        if (task.isFinalState()) {
            log.info("Task already in final state: {}, status: {}", taskId, task.getStatus());
            return true;
        }

        int currentExecutionNo = normalizeExecutionNo(task.getRetryCount());
        if (executionNo != currentExecutionNo) {
            log.info("Skip stale task message: taskId={}, messageExecutionNo={}, dbExecutionNo={}",
                    taskId, executionNo, currentExecutionNo);
            return true;
        }

        TaskStatus currentStatus = task.getStatusEnum();
        if (currentStatus == TaskStatus.PENDING) {
            int rows = analysisTaskMapper.updateStatusWithCheck(
                    taskId, TaskStatus.PENDING.getCode(), TaskStatus.QUEUED.getCode());
            if (rows == 0) {
                AnalysisTask latest = queryByTaskId(taskId);
                if (latest == null || latest.isFinalState()
                        || normalizeExecutionNo(latest.getRetryCount()) != executionNo) {
                    return true;
                }
            }
        }

        int started = analysisTaskMapper.startProcessing(taskId, executionNo);
        if (started == 0) {
            log.info("Start processing skipped: taskId={}, executionNo={}", taskId, executionNo);
            return true;
        }

        try {
            doProcess(taskId, task, executionNo);
            cacheTask(taskId);
            sendTaskEvent(taskId, "COMPLETED", null);
            return true;
        } catch (StaleTaskExecutionException e) {
            log.info("Ignore stale task execution result: {}", taskId);
            return true;
        } catch (Exception e) {
            log.error("Task processing error: {}", taskId, e);
            return handleFailure(taskId, executionNo, e);
        }
    }

    private void doProcess(String taskId, AnalysisTask task, int executionNo) {
        log.info("Task processing started: {}", taskId);
        updateProgressOrThrow(taskId, executionNo, 10);

        String videoUrl = task.getVideoUrl();
        // 生成带签名的临时 URL，AI 服务可以通过这个 URL 下载视频
        String presignedUrl = storageService.getPresignedUrl(
                extractObjectPath(videoUrl),
                aiVideoProvider.getPresignedUrlExpireHours());
        log.info("Generated presigned URL for task: {}, url: {}, originalPath: {}",
                taskId, presignedUrl, videoUrl);
        updateProgressOrThrow(taskId, executionNo, 20);
        
        // RAG 检索构建提示词
        PromptEnvelope promptEnvelope = ragOrchestrator.buildPrompt(task);
        String userPrompt = task.getPrompt();
        log.info("Task {} calling AI - prompt: {}, ragStatus={}", taskId,
                userPrompt != null ? (userPrompt.length() > 100 ? userPrompt.substring(0, 100) + "..." : userPrompt) : "null",
                promptEnvelope.getRetrievalSnapshot() != null ? promptEnvelope.getRetrievalSnapshot().get("status") : "UNKNOWN");
        
        // 调用 AI 分析
        String aiResult = aiService.analyzeVideo(presignedUrl, promptEnvelope);
        updateProgressOrThrow(taskId, executionNo, 80);

        // 完成任务,提取摘要
        String summary = extractSummary(aiResult);
        int rows = analysisTaskMapper.completeTask(taskId, executionNo,
                aiResult, summary, 0, 0L);
        if (rows == 0) {
            throw new StaleTaskExecutionException("Task completion skipped due to stale attempt");
        }
        // 将检索快照保存到 task_rag_context 表，用于调试、审计、优化
        persistRagContext(taskId, promptEnvelope);

        log.info("Task completed: {}", taskId);
    }

    private boolean handleFailure(String taskId, int executionNo, Exception exception) {
        if (exception instanceof StaleTaskExecutionException) {
            return true;
        }

        String errorMessage = truncateError(resolveErrorMessage(exception));
        boolean markedFailed = taskFailureService.markExecutionFailed(
                taskId, executionNo, errorMessage);
        cacheTask(taskId);

        if (markedFailed) {
            sendTaskEvent(taskId, "FAILED", errorMessage);
        }
        return true;
    }

    private void updateProgressOrThrow(String taskId, int executionNo, int progress) {
        int rows = analysisTaskMapper.updateProgress(taskId, executionNo, progress);
        if (rows == 0) {
            throw new StaleTaskExecutionException("Task progress update skipped due to stale attempt");
        }
    }

    private void persistRagContext(String taskId, PromptEnvelope promptEnvelope) {
        try {
            ragOrchestrator.saveTaskContext(taskId, promptEnvelope);
        } catch (Exception e) {
            log.warn("Failed to persist task RAG context: {}", taskId, e);
        }
    }

    private String extractObjectPath(String videoUrl) {
        if (videoUrl == null) {
            return "";
        }
        if (videoUrl.startsWith("/")) {
            String path = videoUrl.substring(1);
            if (path.startsWith("video-ai/")) {
                return path.substring("video-ai/".length());
            }
            return path;
        }
        return videoUrl;
    }

    private String extractSummary(String aiResult) {
        if (aiResult == null || aiResult.isEmpty()) {
            return "";
        }
        try {
            int idx = aiResult.indexOf("\"summary\"");
            if (idx < 0) {
                return aiResult.length() > 200 ? aiResult.substring(0, 200) : aiResult;
            }
            int start = aiResult.indexOf("\"", idx + 10) + 1;
            int end = aiResult.indexOf("\"", start);
            if (start > 0 && end > start) {
                return aiResult.substring(start, end);
            }
        } catch (Exception e) {
            log.warn("Failed to extract summary, using first 200 chars", e);
        }
        return aiResult.length() > 200 ? aiResult.substring(0, 200) : aiResult;
    }

    private AnalysisTask queryByTaskId(String taskId) {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getTaskId, taskId);
        return analysisTaskMapper.selectOne(wrapper);
    }

    private void cacheTask(String taskId) {
        try {
            AnalysisTask task = queryByTaskId(taskId);
            if (task == null) {
                return;
            }
            long ttl = task.isFinalState() ? 3600 : 30;
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(RedisKey.taskDetail(taskId), json, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Task cache write failed: {}", taskId, e);
        }
    }

    private void sendTaskEvent(String taskId, String event, String detail) {
        try {
            kafkaTemplate.send(TopicConstant.TASK_EVENT_TOPIC, taskId,
                    Map.of("taskId", taskId,
                            "event", event,
                            "detail", detail != null ? detail : "",
                            "timestamp", System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("Failed to send task event: {}", taskId, e);
        }
    }

    private int normalizeExecutionNo(Integer executionNo) {
        return executionNo == null ? 0 : executionNo;
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null) {
            return "";
        }
        return errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
    }

    private String resolveErrorMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof AiProviderException aiProviderException) {
                return aiProviderException.getMessage();
            }
            cursor = cursor.getCause();
        }
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
    }

    private static final class StaleTaskExecutionException extends RuntimeException {
        private StaleTaskExecutionException(String message) {
            super(message);
        }
    }
}
