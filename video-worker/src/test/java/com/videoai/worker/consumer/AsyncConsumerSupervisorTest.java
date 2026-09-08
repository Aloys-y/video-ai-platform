package com.videoai.worker.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import static org.mockito.Mockito.*;

class AsyncConsumerSupervisorTest {
    @Test void restartsOnlyAbnormalContainerAndNeverDuringShutdown() {
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var coordinator = mock(AsyncVideoCoordinator.class);
        var container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("video-tasks")).thenReturn(container);
        var supervisor = new AsyncConsumerSupervisor(registry, coordinator);
        when(container.isInExpectedState()).thenReturn(true);
        supervisor.recoverAbnormalStop(); verifyNoInteractions(coordinator);
        when(container.isInExpectedState()).thenReturn(false);
        supervisor.recoverAbnormalStop();
        var order = inOrder(coordinator, container);
        order.verify(coordinator).revokeAll(); order.verify(container).stop(); order.verify(container).start();
        supervisor.onClosing(null); supervisor.recoverAbnormalStop();
        verify(container, times(1)).start();
    }
}
