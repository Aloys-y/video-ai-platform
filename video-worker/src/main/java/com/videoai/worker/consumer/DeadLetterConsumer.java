package com.videoai.worker.consumer;

import com.videoai.infra.kafka.topic.TopicConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 死信消费者
 *
 * 死信队列的意义：
 * 1. 重试耗尽的任务不会被静默丢弃
 * 2. 便于排查问题：哪个任务、什么原因、重试了几次
 * 3. 可以人工介入或定时任务重新处理
 *
 * TODO: 接入告警系统（钉钉/飞书/邮件通知）
 */
@Slf4j
@Component
public class DeadLetterConsumer {

    @KafkaListener(
            topics = TopicConstant.DEAD_LETTER_TOPIC,
            groupId = TopicConstant.MONITOR_GROUP
    )
    public void consume(Map<String, Object> message, Acknowledgment ack) {
        log.error("============ DEAD LETTER TASK ============");
        log.error("taskId: {}", message.get("taskId"));
        log.error("error: {}", message.get("error"));
        log.error("retryCount: {}", message.get("retryCount"));
        log.error("timestamp: {}", message.get("timestamp"));
        log.error("==========================================");

        // TODO: 发送告警通知
        // TODO: 记录到专门的 dead_letter_log 表

        ack.acknowledge();
    }
}
