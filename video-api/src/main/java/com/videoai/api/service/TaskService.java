package com.videoai.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.exception.BusinessException;
import com.videoai.common.message.TaskMessage;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.redis.key.RedisKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 任务服务
 *
 * 设计要点：
 * 1. Redis 缓存 + DB 回源：减少数据库压力
 * 2. 终态任务缓存1小时，非终态缓存30秒
 * 3. 查询失败不影响主流程（缓存降级）
 * 4. 归属校验在 SQL WHERE 中完成（原子操作）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 查询任务详情
     */
    public AnalysisTask getTask(String taskId) {
        // 1. 查缓存
        String cacheKey = RedisKey.taskDetail(taskId);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, AnalysisTask.class);
            } catch (JsonProcessingException e) {
                log.warn("Task cache deserialize failed: {}", taskId, e);
            }
        }

        // 2. 查数据库
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getTaskId, taskId);
        AnalysisTask task = analysisTaskMapper.selectOne(wrapper);

        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }

        // 3. 写缓存（终态缓存久一点）
        try {
            long ttl = task.isFinalState() ? 3600 : 30;
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(task), ttl, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("Task cache write failed: {}", taskId, e);
        }

        return task;
    }

    /**
     * 分页查询用户任务列表
     */
    public Page<AnalysisTask> listUserTasks(Long userId, int pageNum, int pageSize) {
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1 || pageSize > 50) pageSize = 20;

        Page<AnalysisTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getUserId, userId)
                .orderByDesc(AnalysisTask::getCreatedAt);

        return analysisTaskMapper.selectPage(page, wrapper);
    }

    /**
     * 重命名任务
     */
    public AnalysisTask renameTask(String taskId, Long userId, String taskName) {
        int rows = analysisTaskMapper.renameTask(taskId, userId, taskName);
        if (rows == 0) {
            // 反查区分原因
            AnalysisTask task = getTask(taskId);
            if (!task.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.USER_FORBIDDEN);
            }
            throw new BusinessException(ErrorCode.TASK_STATUS_ERROR);
        }

        evictTaskCache(taskId);
        return getTask(taskId);
    }

    /**
     * 逻辑删除任务（状态改为CANCELLED）
     */
    public void deleteTask(String taskId, Long userId) {
        int rows = analysisTaskMapper.logicalDelete(taskId, userId);
        if (rows == 0) {
            AnalysisTask task = getTask(taskId);
            if (!task.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.USER_FORBIDDEN);
            }
            TaskStatus status = task.getStatusEnum();
            if (status != null && !status.isFinalState()) {
                throw new BusinessException(ErrorCode.TASK_PROCESSING, "任务正在处理中，无法删除");
            }
            throw new BusinessException(ErrorCode.TASK_STATUS_ERROR);
        }

        evictTaskCache(taskId);
        log.info("Task deleted: taskId={}, userId={}", taskId, userId);
    }

    /**
     * 重试任务（仅FAILED/DEAD状态）
     */
    public AnalysisTask retryTask(String taskId, Long userId) {
        int rows = analysisTaskMapper.resetForRetry(taskId, userId);
        if (rows == 0) {
            AnalysisTask task = getTask(taskId);
            if (!task.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.USER_FORBIDDEN);
            }
            TaskStatus status = task.getStatusEnum();
            if (status != TaskStatus.FAILED && status != TaskStatus.DEAD) {
                throw new BusinessException(ErrorCode.TASK_STATUS_ERROR, "只有失败或已终止的任务可以重试");
            }
            throw new BusinessException(ErrorCode.TASK_STATUS_ERROR);
        }

        // 重新发送Kafka消息
        AnalysisTask task = getTask(taskId);
        try {
            TaskMessage message = TaskMessage.create(taskId, userId, task.getVideoUrl());
            kafkaTemplate.send(TopicConstant.TASK_TOPIC, taskId, message);
            log.info("Task retry message sent: taskId={}", taskId);
        } catch (Exception e) {
            log.warn("Kafka send failed for retry, taskId={}", taskId, e);
        }

        evictTaskCache(taskId);
        return task;
    }

    /**
     * 清除任务缓存
     */
    private void evictTaskCache(String taskId) {
        try {
            redisTemplate.delete(RedisKey.taskDetail(taskId));
        } catch (Exception e) {
            log.warn("Cache eviction failed for taskId={}", taskId, e);
        }
    }
}
