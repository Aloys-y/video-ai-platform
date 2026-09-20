package com.videoai.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name="notification.mock.enabled", havingValue="true")
public class NotificationKafkaConfig {
    @Bean(destroyMethod="destroy")
    DefaultKafkaProducerFactory<String,String> notificationProducerFactory(
            @Value("${notification.kafka.bootstrap-servers:localhost:9092}") String servers) {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,"all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,1000, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,15000));
    }
    @Bean KafkaTemplate<String,String> notificationKafkaTemplate(DefaultKafkaProducerFactory<String,String> notificationProducerFactory) {
        return new KafkaTemplate<>(notificationProducerFactory);
    }
    @Bean NotificationEventPublisher notificationEventPublisher(KafkaTemplate<String,String> notificationKafkaTemplate,
            ObjectMapper mapper, @Value("${notification.kafka.topic:video.analysis.finished.v1}") String topic) {
        return new NotificationEventPublisher(notificationKafkaTemplate,mapper,topic);
    }
}
