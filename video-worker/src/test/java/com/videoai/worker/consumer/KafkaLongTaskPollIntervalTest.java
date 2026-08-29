package com.videoai.worker.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用秒级时间模拟长任务，验证 max.poll.interval.ms 对消费者组成员资格的影响。
 *
 * 这里不追求与生产环境分钟数等比例，只压缩验证同一个边界关系：
 * 处理时间超过间隔时提交失败，处理时间小于间隔时提交成功。
 */
class KafkaLongTaskPollIntervalTest {

    private static final String BAD_CONFIG_TOPIC = "long-task-bad-config";
    private static final String SAFE_CONFIG_TOPIC = "long-task-safe-config";

    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    static void startKafka() {
        broker = new EmbeddedKafkaKraftBroker(
                1, 1, BAD_CONFIG_TOPIC, SAFE_CONFIG_TOPIC);
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopKafka() {
        if (broker != null) {
            broker.destroy();
        }
    }

    @Test
    void shouldLoseGroupMembershipWhenTaskExceedsMaxPollInterval() throws Exception {
        send(BAD_CONFIG_TOPIC, "task-bad");

        try (KafkaConsumer<String, String> consumer = createConsumer(1_000, 10)) {
            consumer.subscribe(List.of(BAD_CONFIG_TOPIC));
            ConsumerRecord<String, String> record = pollOne(consumer);
            assertEquals("task-bad", record.value());

            // 模拟单次 AI 处理时间超过 max.poll.interval.ms。
            Thread.sleep(2_000);

            // Consumer 已因长时间未 poll 离开消费组，当前批次的 offset 无法提交。
            assertThrows(CommitFailedException.class, () -> consumer.commitSync());
        }
    }

    @Test
    void shouldCommitNormallyWhenTaskFinishesWithinMaxPollInterval() throws Exception {
        send(SAFE_CONFIG_TOPIC, "task-safe");

        try (KafkaConsumer<String, String> consumer = createConsumer(4_000, 1)) {
            consumer.subscribe(List.of(SAFE_CONFIG_TOPIC));
            ConsumerRecord<String, String> record = pollOne(consumer);
            assertEquals("task-safe", record.value());

            // 同样模拟2秒AI处理，但max.poll.interval.ms留有充足余量。
            Thread.sleep(2_000);

            assertDoesNotThrow(() -> consumer.commitSync());
        }
    }

    private static KafkaConsumer<String, String> createConsumer(int maxPollIntervalMs, int maxPollRecords) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "long-task-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        return new KafkaConsumer<>(props);
    }

    private static void send(String topic, String value) throws Exception {
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, value)).get();
        }
    }

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("Timed out waiting for Kafka record");
    }
}
