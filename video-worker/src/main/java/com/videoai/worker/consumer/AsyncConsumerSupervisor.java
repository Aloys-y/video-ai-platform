package com.videoai.worker.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只恢复框架判定的异常停止；正常停止和用户暂停不自动重启。 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "videoai.worker.async-enabled", havingValue = "true")
public class AsyncConsumerSupervisor {
    private final KafkaListenerEndpointRegistry registry;
    private final AsyncVideoCoordinator coordinator;
    private volatile boolean closing;

    @EventListener
    public void onClosing(ContextClosedEvent event) { closing = true; }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void recoverAbnormalStop() {
        if (closing) return;
        var container = registry.getListenerContainer("video-tasks");
        if (container == null || container.isInExpectedState()) return;
        log.error("视频消费容器异常停止，撤销本机执行权后从未提交offset恢复");
        coordinator.revokeAll();
        try {
            container.stop();
            if (!closing) container.start();
        } catch (RuntimeException e) {
            log.error("视频消费容器恢复失败，下一周期重试: type={}", e.getClass().getSimpleName());
        }
    }
}
