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
    private final AudioPrefilterPipeline prefilterPipeline;
    private final com.videoai.worker.config.WorkerExecutionProperties executionProperties;

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
            if (executionNo > currentExecutionNo) throw new UnsettledTaskException("消息代次超前，等待数据库核对");
            log.info("Skip stale task message: taskId={}, messageExecutionNo={}, dbExecutionNo={}",
                    taskId, executionNo, currentExecutionNo);
            return true;
        }

        if (task.getStatusEnum() == TaskStatus.PROCESSING && com.videoai.common.analysis.ExecutionOwnership.current() == null) {
            if (task.getStartedAt() != null && task.getStartedAt().isBefore(java.time.LocalDateTime.now()
                    .minusMinutes(executionProperties.getTaskTimeoutMinutes())))
                return handleFailure(taskId, executionNo, new IllegalStateException("任务处理超过总期限，等待用户重试"));
            throw new UnsettledTaskException("当前执行仍在处理，不能确认重复消息");
        }

        TaskStatus currentStatus = task.getStatusEnum();
        if (currentStatus == TaskStatus.PENDING) {
            int rows = analysisTaskMapper.markQueued(taskId, executionNo);
            if (rows == 0) {
                AnalysisTask latest = queryByTaskId(taskId);
                if (latest == null || latest.isFinalState()
                        || normalizeExecutionNo(latest.getRetryCount()) != executionNo) {
                    return true;
                }
            }
        }

        int started = task.getStatusEnum() == TaskStatus.PROCESSING && com.videoai.common.analysis.ExecutionOwnership.current() != null
                ? 1 : analysisTaskMapper.startProcessing(taskId, executionNo);
        if (started == 0) {
            log.info("Start processing skipped: taskId={}, executionNo={}", taskId, executionNo);
            return requireSettled(taskId, executionNo);
        }

        if (task.getStatusEnum() == TaskStatus.PROCESSING && (task.getAnalysisMode() == null || "DIRECT_VIDEO".equals(task.getAnalysisMode()))
                && com.videoai.common.analysis.ExecutionOwnership.current() != null)
            return handleFailure(taskId, executionNo, new IllegalStateException("原直传调用结果未知，禁止恢复时自动重发"));
        java.time.Instant deadline = com.videoai.common.analysis.ExecutionOwnership.current() == null
                ? java.time.Instant.now().plusSeconds(executionProperties.getTaskTimeoutMinutes() * 60L) : java.time.Instant.MAX;
        try (var budget = com.videoai.common.analysis.ExecutionBudget.bind(deadline)) {
            if ("AUDIO_PREFILTER".equals(task.getAnalysisMode())) {
                var result = prefilterPipeline.run(task, executionNo, extractObjectPath(task.getVideoUrl()), deadline);
                com.videoai.common.analysis.ExecutionBudget.check();
                if (analysisTaskMapper.completeTask(taskId, executionNo, result.markdown(), extractSummary(result.markdown()), null, result.tokensUsed()) != 1)
                    throw new StaleTaskExecutionException("粗筛任务终态写入被拒绝");
                if (result.ragContext() != null) persistRagContext(taskId, result.ragContext());
            } else if (task.getAnalysisMode() == null || "DIRECT_VIDEO".equals(task.getAnalysisMode())) {
                doProcess(taskId, task, executionNo);
            } else throw new IllegalArgumentException("未知的任务分析模式");
            cacheTask(taskId);
            sendTaskEvent(taskId, "COMPLETED", null);
            return true;
        } catch (StaleTaskExecutionException e) {
            log.info("Ignore stale task execution result: {}", taskId);
            return requireSettled(taskId, executionNo);
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException | UnsettledTaskException e) {
            throw new UnsettledTaskException("数据库操作未可靠收敛，保留消息等待核对");
        } catch (Exception e) {
            var owner = com.videoai.common.analysis.ExecutionOwnership.current();
            if(owner != null && !owner.valid()) throw new UnsettledTaskException("执行所有权失效，不写终态");
            if (e instanceof com.videoai.worker.segment.SegmentAnalysisExecutor.BatchFailure batch && batch.persistenceUnsettled())
                throw new UnsettledTaskException("片段持久化尚未收敛，保留消息等待恢复");
            log.error("Task processing error: {}", taskId, e);
            return handleFailure(taskId, executionNo, e);
        }
    }

    private void doProcess(String taskId, AnalysisTask task, int executionNo) throws java.io.IOException {
        log.info("Task processing started: {}", taskId);
        updateProgressOrThrow(taskId, executionNo, 10);

        String videoUrl = task.getVideoUrl();
        // 生成带签名的临时 URL，AI 服务可以通过这个 URL 下载视频
        String presignedUrl = storageService.getPresignedUrl(
                extractObjectPath(videoUrl),
                aiVideoProvider.getPresignedUrlExpireHours());
        log.info("Generated presigned URL for task: {}", taskId);
        updateProgressOrThrow(taskId, executionNo, 20);
        
        // RAG 检索构建提示词
        PromptEnvelope promptEnvelope = ragOrchestrator.buildPrompt(task);
        com.videoai.common.analysis.ExecutionBudget.check();
        String userPrompt = task.getPrompt();
        log.info("Task {} calling AI - prompt: {}, ragStatus={}", taskId,
                userPrompt != null ? (userPrompt.length() > 100 ? userPrompt.substring(0, 100) + "..." : userPrompt) : "null",
                promptEnvelope.getRetrievalSnapshot() != null ? promptEnvelope.getRetrievalSnapshot().get("status") : "UNKNOWN");
        
        // 调用 AI 分析
        String aiResult = aiService.analyzeVideo(presignedUrl, promptEnvelope);
        com.videoai.common.analysis.ExecutionBudget.check();
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
            var failed = queryByTaskId(taskId);
            sendTaskEvent(taskId, failed == null ? "FAILED" : failed.getStatus(), errorMessage);
            return true;
        }
        return requireSettled(taskId, executionNo);
    }

    private boolean requireSettled(String taskId, int no) {
        var latest = queryByTaskId(taskId);
        if (latest == null || latest.isFinalState() || normalizeExecutionNo(latest.getRetryCount()) > no) return true;
        throw new UnsettledTaskException("任务终态尚未确认，不能提交 offset");
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
