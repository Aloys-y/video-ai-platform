package com.videoai.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Outbox Kafka ACK 回调线程池。
 * 回调中包含 MySQL 更新，不能占用 Kafka network thread。
 */
@Configuration
public class OutboxDispatchConfig {

    @Bean("outboxCallbackExecutor")
    public Executor outboxCallbackExecutor(
            @Value("${videoai.outbox.callback-threads:10}") int callbackThreads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("outbox-callback-");
        executor.setCorePoolSize(callbackThreads);
        executor.setMaxPoolSize(callbackThreads);
        executor.setQueueCapacity(callbackThreads * 2);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
