package com.videoai.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.TaskStatus;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
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







    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(analysisTaskMapper);
    }

    @Test
    void shouldReturnPendingTaskWithIncrementedAttemptForManualRetry() {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setUserId(7L);
        task.setStatusEnum(TaskStatus.PENDING);
        task.setAttemptNo(1);
        task.setAnalysisMode("AUDIO_PREFILTER");

        when(analysisTaskMapper.resetForManualRetry("task-1", 7L)).thenReturn(1);
        when(analysisTaskMapper.selectOne(any())).thenReturn(task);

        AnalysisTask result = taskService.retryTask("task-1", 7L);

        assertEquals(1, result.getAttemptNo());
        assertEquals("AUDIO_PREFILTER", result.getAnalysisMode());
        verify(analysisTaskMapper).resetForManualRetry("task-1", 7L);
    }
}
