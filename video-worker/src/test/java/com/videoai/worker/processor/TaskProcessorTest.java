package com.videoai.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.message.TaskMessage;
import com.videoai.common.rag.PromptEnvelope;
import com.videoai.common.enums.TaskStatus;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.rag.service.RagOrchestrator;
import com.videoai.worker.service.AiService;
import com.videoai.worker.service.TaskRetryService;
import com.videoai.worker.service.provider.AiVideoProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskProcessorTest {

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private AiService aiService;

    @Mock
    private StorageService storageService;

    @Mock
    private AiVideoProvider aiVideoProvider;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TaskRetryService taskRetryService;

    @Mock
    private RagOrchestrator ragOrchestrator;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TaskProcessor taskProcessor;

    @BeforeEach
    void setUp() {
        taskProcessor = new TaskProcessor(
                analysisTaskMapper,
                kafkaTemplate,
                aiService,
                storageService,
                aiVideoProvider,
                redisTemplate,
                objectMapper,
                taskRetryService,
                ragOrchestrator);
    }

    @Test
    void shouldPersistRagContextAfterTaskCompletion() throws Exception {
        AnalysisTask task = buildPendingTask();
        TaskMessage message = TaskMessage.builder()
                .taskId("task-1")
                .businessRetryNo(0)
                .build();
        PromptEnvelope promptEnvelope = PromptEnvelope.builder()
                .systemPrompt("system")
                .retrievalContext("context")
                .userPrompt("user")
                .build();

        when(analysisTaskMapper.selectOne(any())).thenReturn(task);
        when(analysisTaskMapper.updateStatusWithCheck("task-1", TaskStatus.PENDING.getCode(), TaskStatus.QUEUED.getCode()))
                .thenReturn(1);
        when(analysisTaskMapper.startProcessing("task-1", 0)).thenReturn(1);
        when(analysisTaskMapper.updateProgress("task-1", 0, 10)).thenReturn(1);
        when(analysisTaskMapper.updateProgress("task-1", 0, 20)).thenReturn(1);
        when(analysisTaskMapper.updateProgress("task-1", 0, 80)).thenReturn(1);
        when(storageService.getPresignedUrl(anyString(), anyInt())).thenReturn("https://example.com/video");
        when(aiVideoProvider.getPresignedUrlExpireHours()).thenReturn(2);
        when(ragOrchestrator.buildPrompt(task)).thenReturn(promptEnvelope);
        when(aiService.analyzeVideo("https://example.com/video", promptEnvelope)).thenReturn("{\"summary\":\"ok\"}");
        when(analysisTaskMapper.completeTask("task-1", 0, "{\"summary\":\"ok\"}", "ok", 0, 0L)).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doNothing().when(ragOrchestrator).saveTaskContext("task-1", promptEnvelope);

        assertTrue(taskProcessor.process(message));

        InOrder inOrder = inOrder(analysisTaskMapper, ragOrchestrator);
        inOrder.verify(analysisTaskMapper).completeTask("task-1", 0, "{\"summary\":\"ok\"}", "ok", 0, 0L);
        inOrder.verify(ragOrchestrator).saveTaskContext("task-1", promptEnvelope);
    }

    @Test
    void shouldSkipRagContextPersistenceWhenCompletionIsStale() {
        AnalysisTask task = buildPendingTask();
        TaskMessage message = TaskMessage.builder()
                .taskId("task-1")
                .businessRetryNo(0)
                .build();
        PromptEnvelope promptEnvelope = PromptEnvelope.builder()
                .systemPrompt("system")
                .retrievalContext("context")
                .userPrompt("user")
                .build();

        when(analysisTaskMapper.selectOne(any())).thenReturn(task);
        when(analysisTaskMapper.updateStatusWithCheck("task-1", TaskStatus.PENDING.getCode(), TaskStatus.QUEUED.getCode()))
                .thenReturn(1);
        when(analysisTaskMapper.startProcessing("task-1", 0)).thenReturn(1);
        when(analysisTaskMapper.updateProgress("task-1", 0, 10)).thenReturn(1);
        when(analysisTaskMapper.updateProgress("task-1", 0, 20)).thenReturn(1);
        when(analysisTaskMapper.updateProgress("task-1", 0, 80)).thenReturn(1);
        when(storageService.getPresignedUrl(anyString(), anyInt())).thenReturn("https://example.com/video");
        when(aiVideoProvider.getPresignedUrlExpireHours()).thenReturn(2);
        when(ragOrchestrator.buildPrompt(task)).thenReturn(promptEnvelope);
        when(aiService.analyzeVideo("https://example.com/video", promptEnvelope)).thenReturn("{\"summary\":\"ok\"}");
        when(analysisTaskMapper.completeTask("task-1", 0, "{\"summary\":\"ok\"}", "ok", 0, 0L)).thenReturn(0);

        assertTrue(taskProcessor.process(message));

        verify(ragOrchestrator, never()).saveTaskContext(anyString(), any());
        verify(taskRetryService, never()).handleExecutionFailure(anyString(), anyInt(), anyString(), anyBoolean());
    }

    private AnalysisTask buildPendingTask() {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId("task-1");
        task.setPrompt("分析这段视频");
        task.setVideoUrl("/video-ai/tasks/demo.mp4");
        task.setRetryCount(0);
        task.setMaxRetry(3);
        task.setStatus(TaskStatus.PENDING.getCode());
        return task;
    }
}
