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
    }

    private ConfigurableEnvironment loadEnvironment(String resourceName) throws IOException {
        ConfigurableEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
