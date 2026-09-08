package com.videoai.worker.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Kafka 监听器配置
 */
@Configuration
public class KafkaListenerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            org.springframework.boot.autoconfigure.kafka.KafkaProperties kafka,
            WorkerExecutionProperties execution,
            com.videoai.worker.consumer.AsyncVideoCoordinator coordinator) {
        execution.validateKafka(kafka);
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        if(execution.isAsyncEnabled()) {
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
            factory.getContainerProperties().setAsyncAcks(true);
            factory.getContainerProperties().setConsumerRebalanceListener(new org.springframework.kafka.listener.ConsumerAwareRebalanceListener() {
                @Override public void onPartitionsRevokedBeforeCommit(org.apache.kafka.clients.consumer.Consumer<?,?> consumer, java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {coordinator.revoke(consumer,partitions);}
                @Override public void onPartitionsLost(org.apache.kafka.clients.consumer.Consumer<?,?> consumer, java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {coordinator.revoke(consumer,partitions);}
            });
        }
        factory.setCommonErrorHandler(unsettledTaskErrorHandler());
        return factory;
    }

    @Bean
    public org.springframework.kafka.listener.DefaultErrorHandler unsettledTaskErrorHandler() {
        var handler = new org.springframework.kafka.listener.DefaultErrorHandler(
                (record, error) -> { throw new org.springframework.kafka.KafkaException("未可靠收敛，保留消息"); },
                new org.springframework.util.backoff.FixedBackOff(1000, org.springframework.util.backoff.FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(), true);
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        // asyncAcks未完成时保留失败记录交由容器重试，避免seek破坏待确认记录账本。
        handler.setSeekAfterError(false);
        return handler;
    }
}
