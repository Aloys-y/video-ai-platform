package com.videoai.worker.scheduler;

import com.videoai.common.domain.TaskOutbox;
import com.videoai.common.message.TaskMessage;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.mysql.mapper.TaskOutboxMapper;
import com.videoai.infra.service.TaskOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherSchedulerTest {

    @Mock
    private TaskOutboxMapper taskOutboxMapper;
    @Mock
    private AnalysisTaskMapper analysisTaskMapper;
    @Mock
    private TaskOutboxService taskOutboxService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private OutboxDispatcherScheduler scheduler;
    private TaskOutbox outbox;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        scheduler = new OutboxDispatcherScheduler(
                taskOutboxMapper,
                analysisTaskMapper,
                taskOutboxService,
                kafkaTemplate,
                directExecutor);
        ReflectionTestUtils.setField(scheduler, "dispatchBatchSize", 50);
        ReflectionTestUtils.setField(scheduler, "sendTimeoutMs", 10_000L);

        outbox = new TaskOutbox();
        outbox.setId(1L);
        outbox.setTaskId("task-1");
        outbox.setBusinessRetryNo(0);
        outbox.setPayload("{}");
        outbox.setSendAttemptCount(0);
    }

    @Test
    void shouldMarkSentOnlyAfterKafkaFutureSucceeds() {
        TaskMessage message = TaskMessage.builder().taskId("task-1").build();
        when(taskOutboxMapper.selectReadyToDispatch(50)).thenReturn(List.of(outbox));
        when(taskOutboxMapper.markSending(1L)).thenReturn(1);
        when(taskOutboxService.parsePayload("{}")).thenReturn(message);
        when(kafkaTemplate.send(TopicConstant.TASK_TOPIC, "task-1", message))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(taskOutboxMapper.markSent(1L)).thenReturn(1);

        scheduler.dispatch();

        verify(taskOutboxMapper).markSent(1L);
        verify(analysisTaskMapper).markQueued("task-1", 0);
        verify(taskOutboxMapper, never()).markSendFailed(eq(1L), any(), any());
    }

    @Test
    void shouldReturnToNewWhenKafkaFutureFails() {
        TaskMessage message = TaskMessage.builder().taskId("task-1").build();
        CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>> failed =
                new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));

        when(taskOutboxMapper.selectReadyToDispatch(50)).thenReturn(List.of(outbox));
        when(taskOutboxMapper.markSending(1L)).thenReturn(1);
        when(taskOutboxService.parsePayload("{}")).thenReturn(message);
        when(kafkaTemplate.send(TopicConstant.TASK_TOPIC, "task-1", message)).thenReturn(failed);

        scheduler.dispatch();

        verify(taskOutboxMapper).markSendFailed(eq(1L), eq("broker unavailable"), any());
        verify(taskOutboxMapper, never()).markSent(1L);
    }

    @Test
    void shouldNotSendWhenAnotherDispatcherWinsClaim() {
        when(taskOutboxMapper.selectReadyToDispatch(50)).thenReturn(List.of(outbox));
        when(taskOutboxMapper.markSending(1L)).thenReturn(0);

        scheduler.dispatch();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }
}
