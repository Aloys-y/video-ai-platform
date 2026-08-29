package com.videoai.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.common.domain.KnowledgeChunk;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.common.enums.KnowledgeIndexStatus;
import com.videoai.common.enums.KnowledgeIndexJobType;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.rag.model.VectorRecord;
import com.videoai.infra.rag.vector.EmbeddingProvider;
import com.videoai.infra.rag.vector.VectorStoreClient;
import com.videoai.rag.model.ChunkedSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexingService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeIndexJobService knowledgeIndexJobService;
    private final KnowledgeChunkingService knowledgeChunkingService;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStoreClient vectorStoreClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public void processJob(String jobId) {
        KnowledgeIndexJob job = knowledgeIndexJobService.getRequiredJob(jobId);
        if (!knowledgeIndexJobService.markProcessing(jobId)) {
            return;
        }

        try {
            if (KnowledgeIndexJobType.REBUILD_ALL.getCode().equals(job.getJobType())) {
                processRebuild(job);
            } else {
                int chunks = indexSingleCard(job, job.getCardCode(), true);
                knowledgeIndexJobService.markSuccess(job.getJobId(), chunks, chunks, 0);
            }
        } catch (Exception e) {
            log.error("Knowledge index job failed: {}", jobId, e);
            knowledgeIndexJobService.markFailed(jobId, truncateError(e.getMessage()));
            if (job.getCardCode() != null) {
                try {
                    knowledgeCardMapper.updateIndexState(job.getBaseCode(), job.getCardCode(),
                            KnowledgeIndexStatus.FAILED.getCode(), job.getJobId(), null, "system");
                } catch (Exception ignore) {
                    log.warn("Failed to update card index state to FAILED: cardCode={}", job.getCardCode(), ignore);
                }
            }
        }
    }

    private void processRebuild(KnowledgeIndexJob job) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        List<KnowledgeCard> cards = knowledgeCardMapper.selectRebuildTargets(base.getBaseCode(), base.getCurrentVersionTag());
        int total = 0;
        int success = 0;
        for (KnowledgeCard card : cards) {
            int chunks = indexSingleCard(job, card.getCardCode(), false);
            total += chunks;
            success += chunks;
        }
        knowledgeIndexJobService.markSuccess(job.getJobId(), total, success, 0);
    }

    /**
     * 索引单张卡片。
     *
     * 分三阶段执行，避免 Milvus HTTP 调用持有 DB 事务：
     * 1. Milvus 清理旧向量（事务外）
     * 2. Embedding + Milvus upsert（事务外）
     * 3. MySQL 写入 chunks + 更新卡片状态（事务内）
     *
     * V1 采用 "Milvus-first, MySQL-second" 策略：若 MySQL 阶段失败，
     * 卡片状态保持 PENDING/INDEXING，下次重试时自动修复。
     */
    protected int indexSingleCard(KnowledgeIndexJob job, String cardCode, boolean markJobSuccess) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        KnowledgeCard card = knowledgeCardMapper.selectByCardCode(base.getBaseCode(), cardCode);
        if (card == null) {
            knowledgeIndexJobService.markFailed(job.getJobId(), "Knowledge card not found");
            return 0;
        }

        // Phase 1: 清理 Milvus 旧向量（事务外，幂等）
        vectorStoreClient.ensureCollection();
        List<KnowledgeChunk> oldChunks = knowledgeChunkMapper.selectByCardCode(base.getBaseCode(), cardCode);
        if (!oldChunks.isEmpty()) {
            vectorStoreClient.deleteByIds(oldChunks.stream().map(KnowledgeChunk::getVectorId).toList());
        }

        if (card.getEnabled() == null || card.getEnabled() == 0) {
            return handleDisabledCard(base.getBaseCode(), cardCode, job.getJobId(), markJobSuccess);
        }

        // Phase 2: 分块 + Embedding + Milvus upsert（事务外）
        List<ChunkedSegment> segments = knowledgeChunkingService.chunkMarkdown(card.getTitle(), card.getContentMarkdown());
        List<VectorRecord> records = buildVectorRecords(card, segments);
        if (!records.isEmpty()) {
            vectorStoreClient.upsert(records);
        }

        // Phase 3: MySQL 持久化 + 状态更新（事务内）
        return persistToDatabase(base.getBaseCode(), card, segments, records, job.getJobId(), markJobSuccess);
    }

    /**
     * 构建 Milvus VectorRecord 列表。
     */
    List<VectorRecord> buildVectorRecords(KnowledgeCard card, List<ChunkedSegment> segments) {
        List<VectorRecord> records = buildMetadataRecords(card, segments);
        for (int index = 0; index < records.size(); index++) {
            records.get(index).setVector(embeddingProvider.embedDocument(buildEmbeddingText(card, segments.get(index))));
        }
        return records;
    }

    List<VectorRecord> buildMetadataRecords(KnowledgeCard card, List<ChunkedSegment> segments) {
        List<VectorRecord> records = new ArrayList<>(segments.size());
        for (ChunkedSegment segment : segments) {
            String vectorId = card.getCardCode() + "_" + segment.getChunkNo();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kb_code", card.getBaseCode());
            metadata.put("version_tag", card.getVersionTag());
            metadata.put("card_code", card.getCardCode());
            metadata.put("category", card.getCategory());
            metadata.put("subject_code", card.getSubjectCode());
            metadata.put("enabled", card.getEnabled());
            metadata.put("timeless", card.getTimeless());
            metadata.put("chunk_no", segment.getChunkNo());
            metadata.put("title", truncate(card.getTitle(), 255));
            metadata.put("heading_path", truncate(segment.getHeadingPath(), 512));
            metadata.put("content_text", truncate(segment.getContentText(), 8000));

            records.add(VectorRecord.builder()
                    .id(vectorId)
                    .fields(metadata)
                    .build());
        }
        return records;
    }

    /**
     * 向量化时补充标题、别名、类别和标题路径；Milvus 中仍保存原始正文用于最终注入。
     * 这样实体名或章节名没有重复出现在正文时，也能被查询稳定召回。
     */
    private String buildEmbeddingText(KnowledgeCard card, ChunkedSegment segment) {
        StringBuilder text = new StringBuilder();
        appendEmbeddingField(text, "Title", card.getTitle());
        appendEmbeddingField(text, "Aliases", card.getAliases());
        appendEmbeddingField(text, "Category", card.getCategory());
        appendEmbeddingField(text, "Subject", card.getSubjectCode());
        appendEmbeddingField(text, "Section", segment.getHeadingPath());
        appendEmbeddingField(text, "Content", segment.getContentText());
        return text.toString().trim();
    }

    private void appendEmbeddingField(StringBuilder text, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        text.append(field).append(": ").append(value.trim()).append('\n');
    }

    /**
     * 处理已禁用卡片：仅清理 MySQL chunks 并更新状态。
     */
    protected int handleDisabledCard(String baseCode, String cardCode, String jobId, boolean markJobSuccess) {
        Integer result = transactionTemplate.execute(status -> {
            knowledgeChunkMapper.deleteByCardCode(baseCode, cardCode);
            knowledgeCardMapper.updateIndexState(baseCode, cardCode,
                    KnowledgeIndexStatus.INDEXED.getCode(), jobId, LocalDateTime.now(), "system");
            if (markJobSuccess) {
                knowledgeIndexJobService.markSuccess(jobId, 0, 0, 0);
            }
            return 0;
        });
        return result == null ? 0 : result;
    }

    /**
     * MySQL 事务：删除旧 chunks → 插入新 chunks → 更新卡片状态。
     */
    protected int persistToDatabase(String baseCode, KnowledgeCard card,
                                    List<ChunkedSegment> segments,
                                    List<VectorRecord> records,
                                    String jobId, boolean markJobSuccess) {
        Integer result = transactionTemplate.execute(status -> {
            // 清理旧 MySQL chunks
            knowledgeChunkMapper.deleteByCardCode(baseCode, card.getCardCode());

            // 批量插入新 chunks
            List<KnowledgeChunk> chunks = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                ChunkedSegment segment = segments.get(i);
                VectorRecord record = records.get(i);

                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setBaseCode(card.getBaseCode());
                chunk.setCardCode(card.getCardCode());
                chunk.setVersionTag(card.getVersionTag());
                chunk.setCategory(card.getCategory());
                chunk.setSubjectCode(card.getSubjectCode());
                chunk.setChunkNo(segment.getChunkNo());
                chunk.setHeadingPath(segment.getHeadingPath());
                chunk.setTitle(card.getTitle());
                chunk.setContentText(segment.getContentText());
                chunk.setContentLength(segment.getContentText().length());
                chunk.setMetadataJson(writeJson(record.getFields()));
                chunk.setVectorId(record.getId());
                chunk.setIndexStatus(KnowledgeIndexStatus.INDEXED.getCode());
                knowledgeChunkMapper.insert(chunk);
                chunks.add(chunk);
            }

            knowledgeCardMapper.updateIndexState(baseCode, card.getCardCode(),
                    KnowledgeIndexStatus.INDEXED.getCode(), jobId, LocalDateTime.now(), "system");
            if (markJobSuccess) {
                knowledgeIndexJobService.markSuccess(jobId, chunks.size(), chunks.size(), 0);
            }
            return chunks.size();
        });
        return result == null ? 0 : result;
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chunk metadata", e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String truncateError(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
