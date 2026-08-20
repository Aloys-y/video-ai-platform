package com.videoai.worker.consumer;

import com.videoai.common.message.KnowledgeIndexMessage;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.rag.service.KnowledgeIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexConsumer {

    private final KnowledgeIndexingService knowledgeIndexingService;

    @KafkaListener(
            topics = TopicConstant.KNOWLEDGE_INDEX_TOPIC,
            groupId = TopicConstant.KNOWLEDGE_INDEX_GROUP,
            concurrency = "1"
    )
    public void consume(KnowledgeIndexMessage message, Acknowledgment ack) {
        log.info("Received knowledge index message: jobId={}, type={}, cardCode={}",
                message.getJobId(), message.getJobType(), message.getCardCode());
        try {
            knowledgeIndexingService.processJob(message.getJobId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Knowledge index consume failed, will retry: jobId={}", message.getJobId(), e);
            throw new RuntimeException("Knowledge index processing failed for jobId=" + message.getJobId(), e);
        }
    }
}
