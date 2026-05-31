package com.videoai.worker.processor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.message.TaskMessage;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.redis.key.RedisKey;
import com.videoai.worker.service.AiService;
import com.videoai.worker.service.provider.AiVideoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 任务处理器
 *
 * 核心流程：消费Kafka消息 → 状态机驱动 → 处理任务 → 完成/失败/重试
 *
 * 设计要点：
 * 1. 状态机校验：每次状态变更都校验前置状态（UPDATE WHERE status = ?），天然幂等
 * 2. 重试机制：失败后重新入队，超过最大次数进入死信
 * 3. 幂等消费：并发冲突时仅一个 Worker 的 UPDATE 命中，其余 rows=0 直接返回
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

    /**
     * 处理任务
     */
    public void process(TaskMessage message) {
        String taskId = message.getTaskId();
        log.info("Processing task: {}, retryCount: {}", taskId, message.getRetryCount());

        // 1. 查询任务
        AnalysisTask task = queryByTaskId(taskId);
        if (task == null) {
            log.warn("Task not found: {}", taskId);
            return;
        }

        // 2. 幂等校验：终态任务跳过
        if (task.isFinalState()) {
            log.info("Task already in final state: {}, status: {}", taskId, task.getStatus());
            return;
        }

        // 3. PENDING → QUEUED（状态机天然幂等：并发时仅一个Worker的UPDATE命中）
        TaskStatus currentStatus = task.getStatusEnum();
        if (currentStatus == TaskStatus.PENDING) {
            int rows = analysisTaskMapper.updateStatusWithCheck(
                    taskId, TaskStatus.PENDING.getCode(), TaskStatus.QUEUED.getCode());
            if (rows == 0) {
                log.info("PENDING→QUEUED transition failed (concurrent): {}", taskId);
                return;
            }
            currentStatus = TaskStatus.QUEUED;
        }

        // 4. QUEUED/RETRYING → PROCESSING
        int started = analysisTaskMapper.startProcessing(taskId);
        if (started == 0) {
            log.info("Start processing failed (invalid state): {}", taskId);
            return;
        }

        // 5. 执行处理
        try {
            doProcess(taskId, task);
        } catch (Exception e) {
            log.error("Task processing error: {}", taskId, e);
            handleFailure(taskId, e.getMessage());
        }
    }

    /**
     * 实际处理逻辑：AI视频分析
     */
    private void doProcess(String taskId, AnalysisTask task) {
        try {
            log.info("Task processing started: {}", taskId);
            analysisTaskMapper.updateProgress(taskId, 10);

            // 1. 生成MinIO预签名URL
            String videoUrl = task.getVideoUrl();
            String presignedUrl = storageService.getPresignedUrl(
                    extractObjectPath(videoUrl),
                    aiVideoProvider.getPresignedUrlExpireHours());
            log.info("Generated presigned URL for task: {}, url: {}, originalPath: {}",
                    taskId, presignedUrl, videoUrl);
            analysisTaskMapper.updateProgress(taskId, 20);

            // 2. 调用AI API分析视频（使用用户自定义prompt）
            String userPrompt = task.getPrompt();
             log.info("Task {} calling AI - prompt: {}", taskId,
                    userPrompt != null ? (userPrompt.length() > 100 ? userPrompt.substring(0, 100) + "..." : userPrompt) : "null");
            String aiResult = aiService.analyzeVideo(presignedUrl, userPrompt);
            analysisTaskMapper.updateProgress(taskId, 80);

            // 3. 提取摘要（简单截取summary字段）
            String summary = extractSummary(aiResult);

            // 4. 完成任务
            analysisTaskMapper.completeTask(taskId,
                    aiResult,
                    summary,
                    0,  // frameCount - 不再抽帧
                    0L  // tokensUsed - GLM响应不直接暴露token数
            );

            log.info("Task completed: {}", taskId);
            cacheTask(taskId);
            sendTaskEvent(taskId, "COMPLETED", null);

        } catch (Exception e) {
            log.error("Task doProcess error: {}", taskId, e);
            handleFailure(taskId, e.getMessage());
        }
    }

    /**
     * 从视频URL提取MinIO对象路径
     * videoUrl格式可能是完整URL或相对路径
     */
    private String extractObjectPath(String videoUrl) {
        if (videoUrl == null) return "";
        // 如果是完整URL（包含bucket名），提取对象路径
        // 格式：/video-ai/uploads/xxx/file.mp4 → uploads/xxx/file.mp4
        if (videoUrl.startsWith("/")) {
            // 去掉开头的bucket名前缀（如果有）
            String path = videoUrl.startsWith("/") ? videoUrl.substring(1) : videoUrl;
            if (path.startsWith("video-ai/")) {
                return path.substring("video-ai/".length());
            }
            return path;
        }
        // 已经是相对路径，直接返回
        return videoUrl;
    }

    /**
     * 从AI返回的JSON中提取summary
     */
    private String extractSummary(String aiResult) {
        if (aiResult == null || aiResult.isEmpty()) return "";
        try {
            // 简单提取："summary": "xxx" 之间的内容
            int idx = aiResult.indexOf("\"summary\"");
            if (idx < 0) return aiResult.length() > 200 ? aiResult.substring(0, 200) : aiResult;
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

    /**
     * 失败处理：重试 or 死信
     */
    private void handleFailure(String taskId, String errorMessage) {
        log.error("Task failed: {}, error: {}", taskId, errorMessage);

        // 截断错误信息，避免超长
        if (errorMessage != null && errorMessage.length() > 500) {
            errorMessage = errorMessage.substring(0, 500);
        }

        analysisTaskMapper.failTask(taskId, errorMessage);
        cacheTask(taskId);

        AnalysisTask task = queryByTaskId(taskId);
        if (task == null) return;

        if (task.canRetry()) {
            log.info("Retrying task: {}, currentRetry: {}", taskId, task.getRetryCount());
            analysisTaskMapper.incrementRetry(taskId);
            cacheTask(taskId);

            // 重新发送到Kafka
            TaskMessage retryMsg = TaskMessage.builder()
                    .taskId(taskId)
                    .uploadId(task.getUploadId())
                    .userId(task.getUserId())
                    .videoUrl(task.getVideoUrl())
                    .retryCount(task.getRetryCount() + 1)
                    .timestamp(System.currentTimeMillis())
                    .build();

            kafkaTemplate.send(TopicConstant.TASK_TOPIC, taskId, retryMsg);
            sendTaskEvent(taskId, "RETRYING", errorMessage);
        } else {
            log.warn("Task retry exhausted → DEAD: {}", taskId);
            analysisTaskMapper.markAsDead(taskId, errorMessage);
            cacheTask(taskId);

            // 投递死信队列
            kafkaTemplate.send(TopicConstant.DEAD_LETTER_TOPIC, taskId,
                    Map.of("taskId", taskId,
                            "error", errorMessage,
                            "retryCount", task.getRetryCount(),
                            "timestamp", System.currentTimeMillis()));
            sendTaskEvent(taskId, "DEAD", errorMessage);
        }
    }

    private AnalysisTask queryByTaskId(String taskId) {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getTaskId, taskId);
        return analysisTaskMapper.selectOne(wrapper);
    }

    /**
     * 状态变更后直接写 Redis，前端轮询 100% 命中，零 MySQL 穿透
     */
    private void cacheTask(String taskId) {
        try {
            AnalysisTask task = queryByTaskId(taskId);
            if (task == null) return;
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
}
