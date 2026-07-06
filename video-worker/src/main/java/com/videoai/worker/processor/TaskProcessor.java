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
import com.videoai.worker.service.TaskRetryService;
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
    private final TaskRetryService taskRetryService;
    private final RagOrchestrator ragOrchestrator;

    public boolean process(TaskMessage message) {
        String taskId = message.getTaskId();
        int businessRetryNo = normalizeRetryNo(message.getBusinessRetryNo());
        log.info("Processing task: {}, businessRetryNo: {}", taskId, businessRetryNo);

        AnalysisTask task = queryByTaskId(taskId);
        if (task == null) {
            log.warn("Task not found: {}", taskId);
            return true;
        }

        if (task.isFinalState()) {
            log.info("Task already in final state: {}, status: {}", taskId, task.getStatus());
            return true;
        }

        int currentRetryCount = normalizeRetryNo(task.getRetryCount());
        if (businessRetryNo != currentRetryCount) {
            log.info("Skip stale task message: taskId={}, messageRetry={}, dbRetry={}",
                    taskId, businessRetryNo, currentRetryCount);
            return true;
        }

        TaskStatus currentStatus = task.getStatusEnum();
        if (currentStatus == TaskStatus.PENDING) {
            int rows = analysisTaskMapper.updateStatusWithCheck(
                    taskId, TaskStatus.PENDING.getCode(), TaskStatus.QUEUED.getCode());
            if (rows == 0) {
                AnalysisTask latest = queryByTaskId(taskId);
                if (latest == null || latest.isFinalState()
                        || normalizeRetryNo(latest.getRetryCount()) != businessRetryNo) {
                    return true;
                }
            }
        }

        int started = analysisTaskMapper.startProcessing(taskId, businessRetryNo);
        if (started == 0) {
            log.info("Start processing skipped: taskId={}, businessRetryNo={}", taskId, businessRetryNo);
            return true;
        }

        try {
            doProcess(taskId, task, businessRetryNo);
            cacheTask(taskId);
            sendTaskEvent(taskId, "COMPLETED", null);
            return true;
        } catch (StaleTaskExecutionException e) {
            log.info("Ignore stale task execution result: {}", taskId);
            return true;
        } catch (Exception e) {
            log.error("Task processing error: {}", taskId, e);
            return handleFailure(taskId, businessRetryNo, e);
        }
    }

    private void doProcess(String taskId, AnalysisTask task, int businessRetryNo) {
        log.info("Task processing started: {}", taskId);
        updateProgressOrThrow(taskId, businessRetryNo, 10);

        String videoUrl = task.getVideoUrl();
        // 生成带签名的临时 URL，AI 服务可以通过这个 URL 下载视频
        String presignedUrl = storageService.getPresignedUrl(
                extractObjectPath(videoUrl),
                aiVideoProvider.getPresignedUrlExpireHours());
        log.info("Generated presigned URL for task: {}, url: {}, originalPath: {}",
                taskId, presignedUrl, videoUrl);
        updateProgressOrThrow(taskId, businessRetryNo, 20);
        
        // RAG 检索构建提示词
        PromptEnvelope promptEnvelope = ragOrchestrator.buildPrompt(task);
        String userPrompt = task.getPrompt();
        log.info("Task {} calling AI - prompt: {}, ragStatus={}", taskId,
                userPrompt != null ? (userPrompt.length() > 100 ? userPrompt.substring(0, 100) + "..." : userPrompt) : "null",
                promptEnvelope.getRetrievalSnapshot() != null ? promptEnvelope.getRetrievalSnapshot().get("status") : "UNKNOWN");
        
        // 调用 AI 分析
        String aiResult = aiService.analyzeVideo(presignedUrl, promptEnvelope);
        updateProgressOrThrow(taskId, businessRetryNo, 80);

        // 完成任务,提取摘要
        String summary = extractSummary(aiResult);
        int rows = analysisTaskMapper.completeTask(taskId, businessRetryNo,
                aiResult, summary, 0, 0L);
        if (rows == 0) {
            throw new StaleTaskExecutionException("Task completion skipped due to stale attempt");
        }
        // 将检索快照保存到 task_rag_context 表，用于调试、审计、优化
        persistRagContext(taskId, promptEnvelope);

        log.info("Task completed: {}", taskId);
    }

    private boolean handleFailure(String taskId, int businessRetryNo, Exception exception) {
        if (exception instanceof StaleTaskExecutionException) {
            return true;
        }

        String errorMessage = truncateError(resolveErrorMessage(exception));
        boolean retryable = isRetryable(exception);

        TaskRetryService.FailureResult result = taskRetryService.handleExecutionFailure(
                taskId, businessRetryNo, errorMessage, retryable);
        cacheTask(taskId);

        if (result.getOutcome() == TaskRetryService.Outcome.RETRY_SCHEDULED) {
            sendTaskEvent(taskId, "RETRYING",
                    errorMessage + " | nextRetryAt=" + result.getNextRetryAt());
            return true;
        }

        if (result.getOutcome() == TaskRetryService.Outcome.DEAD) {
            kafkaTemplate.send(TopicConstant.DEAD_LETTER_TOPIC, taskId,
                    Map.of("taskId", taskId,
                            "error", errorMessage,
                            "businessRetryNo", businessRetryNo,
                            "timestamp", System.currentTimeMillis()));
            sendTaskEvent(taskId, "DEAD", errorMessage);
            return true;
        }

        return true;
    }

    private void updateProgressOrThrow(String taskId, int businessRetryNo, int progress) {
        int rows = analysisTaskMapper.updateProgress(taskId, businessRetryNo, progress);
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

    private int normalizeRetryNo(Integer retryNo) {
        return retryNo == null ? 0 : retryNo;
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

    private boolean isRetryable(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof AiProviderException aiProviderException) {
                return aiProviderException.isRetryable();
            }
            cursor = cursor.getCause();
        }

        String message = throwable.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("connection reset")
                || lower.contains("429")
                || lower.contains("503")
                || lower.contains("502")
                || lower.contains("504");
    }

    private static final class StaleTaskExecutionException extends RuntimeException {
        private StaleTaskExecutionException(String message) {
            super(message);
        }
    }
}
