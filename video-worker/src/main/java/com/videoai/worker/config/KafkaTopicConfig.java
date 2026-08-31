package com.videoai.worker.config;

import com.videoai.infra.kafka.topic.TopicConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 显式声明视频分析任务 Topic，避免依赖 Broker 的自动建 Topic 默认值。
 *
 * Spring Boot 会自动提供 KafkaAdmin：Topic 不存在时创建；现有分区数不足时增加分区。
 * Kafka 不支持减少分区，生产环境调整前应评估 key 到分区映射变化的影响。
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic analysisTaskTopic(
            @Value("${videoai.kafka.task-topic.partitions:6}") int partitions,
            @Value("${videoai.kafka.task-topic.replicas:1}") short replicas) {
        if (partitions < 1) {
            throw new IllegalArgumentException("Task topic partitions must be greater than 0");
        }
        if (replicas < 1) {
            throw new IllegalArgumentException("Task topic replicas must be greater than 0");
        }

        return TopicBuilder.name(TopicConstant.TASK_TOPIC)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
