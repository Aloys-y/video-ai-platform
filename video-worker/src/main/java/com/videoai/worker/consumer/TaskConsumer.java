package com.videoai.worker.consumer;

import com.videoai.common.message.TaskMessage;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.worker.processor.TaskProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 任务消息消费者
 *
 * 设计要点：
 * 1. 手动 ack：处理成功后才提交 offset，保证消息不丢失
 * 2. 消费者组：同一组内负载均衡，不同组广播
 * 3. 幂等消费：TaskProcessor 通过状态机校验实现幂等
 * 4. 异常处理：catch 后 ack，避免阻塞后续消息；失败任务由重试机制处理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConsumer {

    private final TaskProcessor taskProcessor;

    /**
     * 消费任务消息
     * - groupId: 同组消费者共同分担消息
     * - concurrency: 并发消费者数量
     */
    @KafkaListener(
            topics = TopicConstant.TASK_TOPIC,
            groupId = TopicConstant.WORKER_GROUP,
            concurrency = "${videoai.worker.concurrency:3}"
    )
    public void consume(TaskMessage message, Acknowledgment ack) {
        String taskId = message.getTaskId();
        log.info("Received task message: taskId={}, retryCount={}, userId={}",
                taskId, message.getRetryCount(), message.getUserId());

        try {
            taskProcessor.process(message);
        } catch (Exception e) {
            log.error("Task consumer error: taskId={}", taskId, e);
            // 不抛异常，让重试机制在 TaskProcessor 内部处理
        } finally {
            // 手动提交 offset
            ack.acknowledge();
        }
    }
}
