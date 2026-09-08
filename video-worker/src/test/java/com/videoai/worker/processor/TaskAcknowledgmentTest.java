package com.videoai.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.message.TaskMessage;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.rag.service.RagOrchestrator;
import com.videoai.worker.config.WorkerExecutionProperties;
import com.videoai.worker.consumer.TaskConsumer;
import com.videoai.worker.service.*;
import com.videoai.worker.service.provider.AiVideoProvider;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskAcknowledgmentTest {
    AnalysisTaskMapper tasks; TaskFailureService failure; AudioPrefilterPipeline pipeline; TaskConsumer consumer;
    Acknowledgment ack; AnalysisTask task; TaskMessage message;
    @BeforeEach void setup() {
        tasks = mock(AnalysisTaskMapper.class); failure = mock(TaskFailureService.class); pipeline = mock(AudioPrefilterPipeline.class);
        ack = mock(Acknowledgment.class); task = new AnalysisTask(); task.setTaskId("task"); task.setRetryCount(0);
        task.setStatus("QUEUED"); task.setAnalysisMode("AUDIO_PREFILTER"); task.setVideoUrl("/video-ai/source.mp4");
        when(tasks.selectOne(any())).thenReturn(task); when(tasks.startProcessing("task", 0)).thenReturn(1);
        var processor = new TaskProcessor(tasks, mock(KafkaTemplate.class), mock(AiService.class), mock(StorageService.class),
                mock(AiVideoProvider.class), mock(StringRedisTemplate.class), new ObjectMapper(), failure, mock(RagOrchestrator.class),
                pipeline, new WorkerExecutionProperties());
        consumer = new TaskConsumer(processor); message = TaskMessage.builder().taskId("task").businessRetryNo(0).build();
    }
    @Test void completionMustPersistBeforeAckAndModeUsesDatabase() throws Exception {
        when(pipeline.run(eq(task), eq(0), eq("source.mp4"), any())).thenReturn(new AudioPrefilterPipeline.Result("report", 17L, null));
        when(tasks.completeTask("task", 0, "report", "report", null, 17L)).thenReturn(1);
        consumer.consume(message, ack);
        var order = inOrder(pipeline, tasks, ack);
        order.verify(pipeline).run(eq(task), eq(0), eq("source.mp4"), any());
        order.verify(tasks).completeTask("task", 0, "report", "report", null, 17L); order.verify(ack).acknowledge();
    }
    @Test void processingRedeliveryIsNotBusinessCompletion() {
        task.setStatus("PROCESSING"); task.setStartedAt(LocalDateTime.now());
        assertThrows(UnsettledTaskException.class, () -> consumer.consume(message, ack));
        verifyNoInteractions(pipeline, ack); verify(tasks, never()).startProcessing(anyString(), anyInt());
    }
    @Test void failedClaimRequiresProofOfTerminalState() {
        when(tasks.startProcessing("task", 0)).thenReturn(0);
        assertThrows(UnsettledTaskException.class, () -> consumer.consume(message, ack)); verifyNoInteractions(ack, pipeline);
        task.setStatus("CANCELLED"); consumer.consume(message, ack); verify(ack).acknowledge();
    }
    @Test void failedTerminalWriteAndDatabaseOutageKeepMessageUnacknowledged() throws Exception {
        when(pipeline.run(any(), anyInt(), anyString(), any())).thenReturn(new AudioPrefilterPipeline.Result("report", null, null));
        when(tasks.completeTask(anyString(), anyInt(), anyString(), anyString(), isNull(), isNull()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        assertThrows(UnsettledTaskException.class, () -> consumer.consume(message, ack)); verifyNoInteractions(ack, failure);
    }
    @Test void uncertainTerminalCommitDoesNotMarkBusinessFailureOrAcknowledge() throws Exception {
        when(pipeline.run(any(), anyInt(), anyString(), any())).thenReturn(new AudioPrefilterPipeline.Result("report", null, null));
        when(tasks.completeTask(anyString(), anyInt(), anyString(), anyString(), isNull(), isNull()))
                .thenThrow(new org.springframework.transaction.TransactionSystemException("commit outcome unknown"));
        assertThrows(UnsettledTaskException.class, () -> consumer.consume(message, ack));
        verifyNoInteractions(ack, failure);
    }
    @Test void businessFailureAcknowledgesOnlyAfterFailurePersistence() throws Exception {
        when(pipeline.run(any(), anyInt(), anyString(), any())).thenThrow(new java.io.IOException("invalid candidate"));
        when(failure.markExecutionFailed("task", 0, "invalid candidate")).thenReturn(true);
        consumer.consume(message, ack);
        var order = inOrder(failure, ack); order.verify(failure).markExecutionFailed("task", 0, "invalid candidate"); order.verify(ack).acknowledge();
    }
    @Test void failedFailureWriteDoesNotConfirmAndOldGenerationCannotRun() throws Exception {
        when(pipeline.run(any(), anyInt(), anyString(), any())).thenThrow(new java.io.IOException("failure"));
        assertThrows(UnsettledTaskException.class, () -> consumer.consume(message, ack)); verifyNoInteractions(ack);
        task.setRetryCount(1); consumer.consume(message, ack); verify(ack).acknowledge();
        verify(pipeline, times(1)).run(any(), anyInt(), anyString(), any());
    }
    @Test void orphanedProcessingEventuallyFailsWithoutRestartingModel() {
        task.setStatus("PROCESSING"); task.setStartedAt(LocalDateTime.now().minusMinutes(31));
        when(failure.markExecutionFailed(eq("task"), eq(0), anyString())).thenReturn(true);
        consumer.consume(message, ack); verify(ack).acknowledge(); verifyNoInteractions(pipeline);
    }
}
