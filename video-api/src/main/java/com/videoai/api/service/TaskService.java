package com.videoai.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.exception.BusinessException;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.redis.key.RedisKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 任务查询服务
 *
 * 设计要点：
 * 1. Redis 缓存 + DB 回源：减少数据库压力
 * 2. 终态任务缓存1小时，非终态缓存30秒
 * 3. 查询失败不影响主流程（缓存降级）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

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
}
