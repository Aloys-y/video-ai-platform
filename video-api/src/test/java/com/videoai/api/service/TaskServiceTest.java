package com.videoai.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.TaskStatus;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.service.TaskOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TaskOutboxService taskOutboxService;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                analysisTaskMapper,
                redisTemplate,
                objectMapper,
                taskOutboxService);
    }

    @Test
    void shouldCreateOutboxWithIncrementedExecutionNoForManualRetry() {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setUserId(7L);
        task.setStatusEnum(TaskStatus.PENDING);
        task.setRetryCount(1);

        when(analysisTaskMapper.resetForManualRetry("task-1", 7L)).thenReturn(1);
        when(analysisTaskMapper.selectOne(any())).thenReturn(task);

        AnalysisTask result = taskService.retryTask("task-1", 7L);

        assertEquals(1, result.getRetryCount());
        verify(taskOutboxService).createExecuteOutbox(
                eq(task), eq(1), any(LocalDateTime.class));
    }
}
