package com.videoai.worker.consumer;

import com.videoai.common.analysis.ExecutionOwnership;
import com.videoai.common.message.TaskMessage;
import com.videoai.worker.ownership.TaskLeaseService;
import com.videoai.worker.processor.TaskProcessor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.support.Acknowledgment;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Timeout(15)
class AsyncVideoCoordinatorTest {
    TaskMessage message(String id) { return TaskMessage.builder().taskId(id).businessRetryNo(0).build(); }

    @Test void nextRecordCanArriveBeforePreviousLeaseReleaseFinishes() throws Exception {
        var leases=mock(TaskLeaseService.class); var processor=mock(TaskProcessor.class);
        var terminal=new AtomicBoolean(); var releasing=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(leases.settled(anyString(),eq(0))).thenAnswer(i->terminal.get());
        when(leases.claim(anyString(),eq(0))).thenReturn(new ExecutionOwnership.Token("first",0,"owner",System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));
        when(processor.process(any())).thenAnswer(i->{terminal.set(true);return true;});
        doAnswer(i->{releasing.countDown();release.await();return null;}).when(leases).release(any());
        var consumer=mock(Consumer.class); var firstAck=mock(Acknowledgment.class);var secondAck=mock(Acknowledgment.class);
        try(var coordinator=new AsyncVideoCoordinator(processor,leases)) {
            coordinator.accept(consumer,new TopicPartition("tasks",0),message("first"),firstAck);
            assertTrue(releasing.await(3,TimeUnit.SECONDS));verify(firstAck).acknowledge();
            assertDoesNotThrow(()->coordinator.accept(consumer,new TopicPartition("tasks",0),message("second"),secondAck));
            verify(secondAck,timeout(3000)).acknowledge();
        } finally {release.countDown();}
    }

    @Test void rejectedSubmissionRemainsUnacknowledgedAndIsRetriedAfterCapacityReturns() throws Exception {
        var leases=mock(TaskLeaseService.class);var processor=mock(TaskProcessor.class);
        var entered=new CountDownLatch(3);var release=new CountDownLatch(1);var terminal=ConcurrentHashMap.<String>newKeySet();
        when(leases.settled(anyString(),eq(0))).thenAnswer(i->terminal.contains(i.<String>getArgument(0)));
        when(leases.claim(anyString(),eq(0))).thenAnswer(i->new ExecutionOwnership.Token(i.getArgument(0),0,"owner",System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));
        when(processor.process(any())).thenAnswer(i->{entered.countDown();release.await();terminal.add(i.<TaskMessage>getArgument(0).getTaskId());return true;});
        try(var coordinator=new AsyncVideoCoordinator(processor,leases)) {
            var acks=new java.util.ArrayList<Acknowledgment>();
            // 模拟撤销中的旧线程仍占池时，新分配记录到达：3运行+3排队，第7个待提交。
            for(int i=0;i<7;i++){
                var ack=mock(Acknowledgment.class);acks.add(ack);
                coordinator.accept(mock(Consumer.class),new TopicPartition("tasks",i),message("task-"+i),ack);
            }
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(coordinator.stats().rejectedSubmissions()>0);
            for(var ack:acks)verifyNoInteractions(ack);
            release.countDown();for(var ack:acks)verify(ack,timeout(5000)).acknowledge();
            assertEquals(7,terminal.size());verify(processor,times(7)).process(any());
        } finally {release.countDown();}
    }

    @Test void databaseUnavailableDoesNotAcknowledgeOrInvokeModelAndTerminalReplaySkipsProcessing() {
        var leases=mock(TaskLeaseService.class);var processor=mock(TaskProcessor.class);var ack=mock(Acknowledgment.class);
        when(leases.settled("task",0)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).thenReturn(true);
        try(var coordinator=new AsyncVideoCoordinator(processor,leases)) {
            coordinator.accept(mock(Consumer.class),new TopicPartition("tasks",0),message("task"),ack);
            verify(leases,timeout(1000)).settled("task",0);verifyNoInteractions(ack,processor);
            verify(ack,timeout(4000)).acknowledge();verifyNoInteractions(processor);
        }
    }
}
