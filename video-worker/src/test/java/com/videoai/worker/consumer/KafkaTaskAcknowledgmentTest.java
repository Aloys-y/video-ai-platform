package com.videoai.worker.consumer;

import com.videoai.common.message.TaskMessage;
import com.videoai.worker.config.KafkaListenerConfig;
import com.videoai.worker.processor.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Timeout(90)
class KafkaTaskAcknowledgmentTest {
    @Test void unsettledMessageRedeliversAndCommitsOnlyAfterDurableOutcome() throws Exception {
        String topic = "p5-ack", group = "p5-" + UUID.randomUUID();
        var broker = new EmbeddedKafkaKraftBroker(1, 1, topic); broker.afterPropertiesSet();
        var persisted = new AtomicBoolean(); var attempts = new AtomicInteger();
        var secondAttempt = new CountDownLatch(1); var allowPersistence = new CountDownLatch(1); var acknowledged = new CountDownLatch(1);
        var processor = mock(TaskProcessor.class);
        when(processor.process(any())).thenAnswer(i -> {
            if (attempts.incrementAndGet() == 1) throw new UnsettledTaskException("数据库暂不可用");
            secondAttempt.countDown();
            if (!allowPersistence.await(10, TimeUnit.SECONDS)) throw new UnsettledTaskException("测试等待超时");
            persisted.set(true); return true;
        });
        var consumer = new TaskConsumer(processor);
        Map<String, Object> props = KafkaTestUtils.consumerProps(group, "false", broker);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1); props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 60000);
        var containerProps = new ContainerProperties(topic); containerProps.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        containerProps.setMessageListener((AcknowledgingMessageListener<String, String>) (record, ack) -> {
            consumer.consume(TaskMessage.builder().taskId(record.value()).businessRetryNo(0).build(), () -> {
                assertTrue(persisted.get()); ack.acknowledge(); acknowledged.countDown();
            });
        });
        var container = new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<String, String>(props), containerProps);
        container.setCommonErrorHandler(new KafkaListenerConfig().unsettledTaskErrorHandler());
        try (var producer = new KafkaProducer<String, String>(KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer());
             var observer = new KafkaConsumer<String, String>(props)) {
            container.start(); ContainerTestUtils.waitForAssignment(container, 1);
            producer.send(new ProducerRecord<>(topic, "key", "task")).get(5, TimeUnit.SECONDS);
            assertTrue(secondAttempt.await(10, TimeUnit.SECONDS));
            var partition = new TopicPartition(topic, 0);
            var before = observer.committed(Set.of(partition)).get(partition);
            assertTrue(before == null || before.offset() == 0); assertFalse(persisted.get());
            allowPersistence.countDown(); assertTrue(acknowledged.await(10, TimeUnit.SECONDS));
            assertEquals(1, observer.committed(Set.of(partition)).get(partition).offset());
            assertEquals(2, attempts.get());
        } finally { allowPersistence.countDown(); container.stop(); broker.destroy(); }
    }
}
