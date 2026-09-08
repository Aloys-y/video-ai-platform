package com.videoai.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证长任务 Kafka 参数能够从实际配置文件绑定到 Spring Boot KafkaProperties。
 */
class KafkaConsumerConfigurationTest {

    @Test void asyncProfileBuildsManualAsyncContainerWithoutVideoDeadlineCoupling() throws IOException {
        var environment = loadEnvironment("application-async.yml");
        var kafka = Binder.get(environment).bind("spring.kafka", Bindable.of(KafkaProperties.class)).orElseThrow(IllegalStateException::new);
        var execution = Binder.get(environment).bind("videoai.worker", Bindable.of(WorkerExecutionProperties.class)).orElseThrow(IllegalStateException::new);
        org.junit.jupiter.api.Assertions.assertTrue(execution.isAsyncEnabled());
        assertEquals("300000", kafka.getConsumer().getProperties().get("max.poll.interval.ms"));
        assertEquals(java.time.Duration.ofSeconds(1), kafka.getListener().getPollTimeout());
        execution.validateKafka(kafka); // 视频耗时提示30分钟，poll期限5分钟仍合法。
        var factory = new KafkaListenerConfig().kafkaListenerContainerFactory(
                org.mockito.Mockito.mock(org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer.class),
                org.mockito.Mockito.mock(org.springframework.kafka.core.ConsumerFactory.class), kafka, execution,
                org.mockito.Mockito.mock(com.videoai.worker.consumer.AsyncVideoCoordinator.class));
        assertEquals(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL, factory.getContainerProperties().getAckMode());
        org.junit.jupiter.api.Assertions.assertTrue(factory.getContainerProperties().isAsyncAcks());
        org.junit.jupiter.api.Assertions.assertNotNull(factory.getContainerProperties().getConsumerRebalanceListener());
        var dataSource = Binder.get(environment).bind("spring.datasource.druid", Bindable.of(com.alibaba.druid.pool.DruidDataSource.class)).orElseThrow(IllegalStateException::new);
        try { assertEquals(5000, dataSource.getMaxWait()); assertEquals(5000, dataSource.getConnectTimeout()); assertEquals(10000, dataSource.getSocketTimeout()); }
        finally { dataSource.close(); }
    }

    @Test
    void shouldBindSafeLongTaskDefaultsFromApplicationYaml() throws IOException {
        assertLongTaskSettings("application.yml");
    }

    @Test
    void shouldBindSafeLongTaskDefaultsFromDevTemplate() throws IOException {
        assertLongTaskSettings("application-dev.yml.example");
    }

    @Test
    void shouldBindWorkerAndTaskTopicTopologyDefaults() throws IOException {
        ConfigurableEnvironment environment = loadEnvironment("application.yml");

        assertEquals(3, environment.getProperty("videoai.worker.concurrency", Integer.class));
        assertEquals(6,
                environment.getProperty("videoai.kafka.task-topic.partitions", Integer.class));
        assertEquals(1,
                environment.getProperty("videoai.kafka.task-topic.replicas", Integer.class));
    }

    private void assertLongTaskSettings(String resourceName) throws IOException {
        ConfigurableEnvironment environment = loadEnvironment(resourceName);

        KafkaProperties kafkaProperties = Binder.get(environment)
                .bind("spring.kafka", Bindable.of(KafkaProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                        "未能从 " + resourceName + " 绑定 spring.kafka 配置"));

        assertEquals(1, kafkaProperties.getConsumer().getMaxPollRecords());
        assertEquals("2400000",
                kafkaProperties.getConsumer().getProperties().get("max.poll.interval.ms"));
        assertEquals(false, kafkaProperties.getConsumer().getEnableAutoCommit());
        new WorkerExecutionProperties().validateKafka(kafkaProperties);
    }

    private ConfigurableEnvironment loadEnvironment(String resourceName) throws IOException {
        ConfigurableEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    @Test void unsafeOverridesAreRejectedAndErrorHandlerNeverConfirmsRecovery() {
        var kafka = new KafkaProperties(); kafka.getConsumer().setMaxPollRecords(1); kafka.getConsumer().setEnableAutoCommit(false);
        kafka.getConsumer().getProperties().put("max.poll.interval.ms", "2400000");
        var execution = new WorkerExecutionProperties(); execution.validateKafka(kafka);
        kafka.getConsumer().setMaxPollRecords(2);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> execution.validateKafka(kafka));
        kafka.getConsumer().setMaxPollRecords(1); kafka.getConsumer().setEnableAutoCommit(true);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> execution.validateKafka(kafka));
        kafka.getConsumer().setEnableAutoCommit(false); kafka.getConsumer().getProperties().put("max.poll.interval.ms", "1800000");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> execution.validateKafka(kafka));
        org.junit.jupiter.api.Assertions.assertFalse(new KafkaListenerConfig().unsettledTaskErrorHandler().isAckAfterHandle());
    }
}
