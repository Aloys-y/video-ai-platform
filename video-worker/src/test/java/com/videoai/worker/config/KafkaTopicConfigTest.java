package com.videoai.worker.config;

import com.videoai.infra.kafka.topic.TopicConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaTopicConfigTest {

    private final KafkaTopicConfig config = new KafkaTopicConfig();

    @Test
    void shouldCreateTaskTopicWithConfiguredTopology() {
        NewTopic topic = config.analysisTaskTopic(6, (short) 1);

        assertEquals(TopicConstant.TASK_TOPIC, topic.name());
        assertEquals(6, topic.numPartitions());
        assertEquals((short) 1, topic.replicationFactor());
    }

    @Test
    void shouldRejectInvalidTopicTopology() {
        assertThrows(IllegalArgumentException.class,
                () -> config.analysisTaskTopic(0, (short) 1));
        assertThrows(IllegalArgumentException.class,
                () -> config.analysisTaskTopic(6, (short) 0));
    }
}
