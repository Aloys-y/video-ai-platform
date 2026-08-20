package com.videoai.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.enums.KnowledgeIndexJobStatus;
import com.videoai.common.enums.KnowledgeIndexJobType;
import com.videoai.common.exception.BusinessException;
import com.videoai.common.message.KnowledgeIndexMessage;
import com.videoai.common.utils.IdGenerator;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.infra.mysql.mapper.KnowledgeIndexJobMapper;
import com.videoai.infra.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexJobService {

    private final KnowledgeIndexJobMapper knowledgeIndexJobMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    @Transactional
    public KnowledgeIndexJob createCardUpsertJob(String baseCode, String cardCode, String createdBy) {
        return createJob(baseCode, KnowledgeIndexJobType.UPSERT_CARD.getCode(), cardCode, createdBy,
                Map.of("cardCode", cardCode));
    }

    @Transactional
    public KnowledgeIndexJob createRebuildJob(String baseCode, String createdBy) {
        return createJob(baseCode, KnowledgeIndexJobType.REBUILD_ALL.getCode(), null, createdBy,
                Map.of("baseCode", baseCode));
    }

    public KnowledgeIndexJob getRequiredJob(String jobId) {
        KnowledgeIndexJob job = knowledgeIndexJobMapper.selectByJobId(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_INDEX_JOB_NOT_FOUND);
        }
        return job;
    }

    public List<KnowledgeIndexJob> selectReadyToDispatch() {
        return knowledgeIndexJobMapper.selectReadyToDispatch(ragProperties.getDispatchBatchSize());
    }

    public boolean markQueued(Long id) {
        return knowledgeIndexJobMapper.markQueued(id) > 0;
    }

    public void dispatch(KnowledgeIndexJob job) {
        KnowledgeIndexMessage message = KnowledgeIndexMessage.builder()
                .jobId(job.getJobId())
                .baseCode(job.getBaseCode())
                .jobType(job.getJobType())
                .cardCode(job.getCardCode())
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            kafkaTemplate.send(TopicConstant.KNOWLEDGE_INDEX_TOPIC, job.getJobId(), message)
                    .get(ragProperties.getDispatchSendTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dispatching knowledge index job", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to dispatch knowledge index job to Kafka", e);
        }
    }

    public boolean markProcessing(String jobId) {
        return knowledgeIndexJobMapper.markProcessing(jobId) > 0;
    }

    public void markSuccess(String jobId, int totalChunks, int successChunks, int failedChunks) {
        knowledgeIndexJobMapper.markSuccess(jobId, totalChunks, successChunks, failedChunks);
    }

    public void markFailed(String jobId, String errorMessage) {
        knowledgeIndexJobMapper.markFailed(jobId, errorMessage);
    }

    /**
     * 修复调度器在“状态已置为 QUEUED、Kafka 尚未确认”窗口宕机造成的卡死任务，
     * 并把超时 PROCESSING 任务显式终止，避免管理端永久显示处理中。
     */
    public RecoveryResult recoverStaleJobs() {
        LocalDateTime now = LocalDateTime.now();
        int requeued = knowledgeIndexJobMapper.recoverStaleQueued(
                now.minusSeconds(ragProperties.getQueuedRecoveryTimeoutSeconds()));
        int failed = knowledgeIndexJobMapper.failStaleProcessing(
                now.minusSeconds(ragProperties.getProcessingTimeoutSeconds()),
                now.minusSeconds(ragProperties.getRebuildProcessingTimeoutSeconds()));
        return new RecoveryResult(requeued, failed);
    }

    private KnowledgeIndexJob createJob(String baseCode, String jobType, String cardCode, String createdBy,
                                        Map<String, Object> payload) {
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId(IdGenerator.generateKnowledgeJobId());
        job.setBaseCode(baseCode);
        job.setJobType(jobType);
        job.setCardCode(cardCode);
        job.setStatus(KnowledgeIndexJobStatus.NEW.getCode());
        job.setPayloadJson(writeJson(payload));
        job.setTotalChunks(0);
        job.setSuccessChunks(0);
        job.setFailedChunks(0);
        job.setCreatedBy(createdBy);
        knowledgeIndexJobMapper.insert(job);
        return job;
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize knowledge index job payload", e);
            throw new IllegalStateException("Failed to serialize knowledge index job payload", e);
        }
    }

    public record RecoveryResult(int requeued, int failed) {
    }
}
