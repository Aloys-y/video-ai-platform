package com.videoai.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.common.domain.KnowledgeChunk;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.common.dto.request.KnowledgeCardUpsertRequest;
import com.videoai.common.dto.request.KnowledgeMarkdownDocument;
import com.videoai.common.dto.response.KnowledgeCardPreviewResponse;
import com.videoai.common.dto.response.KnowledgeCardResponse;
import com.videoai.common.dto.response.KnowledgeMarkdownImportItemResponse;
import com.videoai.common.dto.response.KnowledgeMarkdownImportResponse;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.enums.KnowledgeCategory;
import com.videoai.common.enums.KnowledgeIndexStatus;
import com.videoai.common.exception.BusinessException;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.mysql.mapper.KnowledgeIndexJobMapper;
import com.videoai.infra.rag.vector.VectorStoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCardService {

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$");

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeIndexJobService knowledgeIndexJobService;
    private final KnowledgeIndexJobMapper knowledgeIndexJobMapper;
    private final VectorStoreClient vectorStoreClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public KnowledgeCardResponse create(KnowledgeCardUpsertRequest request, String operator) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        KnowledgeCard existing = knowledgeCardMapper.selectByCardCode(base.getBaseCode(), request.getCardCode());
        if (existing != null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CARD_CODE_EXISTS);
        }

        KnowledgeCard card = new KnowledgeCard();
        applyRequest(card, base, request, operator);
        card.setIndexStatus(KnowledgeIndexStatus.PENDING.getCode());
        knowledgeCardMapper.insert(card);

        String jobId = knowledgeIndexJobService.createCardUpsertJob(base.getBaseCode(), card.getCardCode(), operator).getJobId();
        knowledgeCardMapper.updateIndexState(base.getBaseCode(), card.getCardCode(),
                KnowledgeIndexStatus.PENDING.getCode(), jobId, null, operator);
        return toResponse(knowledgeCardMapper.selectByCardCode(base.getBaseCode(), card.getCardCode()));
    }

    @Transactional
    public KnowledgeCardResponse update(String cardCode, KnowledgeCardUpsertRequest request, String operator) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        KnowledgeCard existing = getRequiredCard(base.getBaseCode(), cardCode);
        applyRequest(existing, base, request, operator);
        existing.setCardCode(cardCode);
        existing.setIndexStatus(KnowledgeIndexStatus.PENDING.getCode());
        knowledgeCardMapper.updateById(existing);

        String jobId = knowledgeIndexJobService.createCardUpsertJob(base.getBaseCode(), cardCode, operator).getJobId();
        knowledgeCardMapper.updateIndexState(base.getBaseCode(), cardCode,
                KnowledgeIndexStatus.PENDING.getCode(), jobId, null, operator);
        return toResponse(knowledgeCardMapper.selectByCardCode(base.getBaseCode(), cardCode));
    }

    public KnowledgeCardResponse get(String cardCode) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        return toResponse(getRequiredCard(base.getBaseCode(), cardCode));
    }

    /**
     * 清空知识库：先清 Milvus（事务外），再删 MySQL（事务内）。
     */
    public int cleanup() {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(
                new LambdaQueryWrapper<KnowledgeCard>()
                        .eq(KnowledgeCard::getBaseCode, base.getBaseCode()));
        if (cards.isEmpty()) return 0;

        // Phase 1: Clear Milvus (outside transaction)
        for (KnowledgeCard card : cards) {
            try {
                List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectByCardCode(base.getBaseCode(), card.getCardCode());
                if (!chunks.isEmpty()) {
                    vectorStoreClient.deleteByIds(chunks.stream().map(KnowledgeChunk::getVectorId).toList());
                }
            } catch (Exception e) {
                log.warn("Failed to delete Milvus vectors for card {}: {}", card.getCardCode(), e.getMessage());
            }
        }

        // Phase 2: Delete MySQL (transactional)
        Integer deleted = transactionTemplate.execute(status -> cleanupDatabase(base.getBaseCode()));
        return deleted == null ? 0 : deleted;
    }

    protected int cleanupDatabase(String baseCode) {
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getBaseCode, baseCode));
        int count = knowledgeCardMapper.delete(new LambdaQueryWrapper<KnowledgeCard>()
                .eq(KnowledgeCard::getBaseCode, baseCode));
        knowledgeIndexJobMapper.delete(new LambdaQueryWrapper<KnowledgeIndexJob>()
                .eq(KnowledgeIndexJob::getBaseCode, baseCode));
        return count;
    }

    /**
     * 删除知识卡片：先清理 Milvus 向量（事务外），再删除 MySQL chunks + 卡片（事务内）。
     */
    public void delete(String cardCode) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        KnowledgeCard card = getRequiredCard(base.getBaseCode(), cardCode);

        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectByCardCode(base.getBaseCode(), cardCode);
        if (!chunks.isEmpty()) {
            vectorStoreClient.deleteByIds(chunks.stream().map(KnowledgeChunk::getVectorId).toList());
        }
        transactionTemplate.executeWithoutResult(status -> {
            knowledgeChunkMapper.deleteByCardCode(base.getBaseCode(), cardCode);
            knowledgeCardMapper.deleteById(card.getId());
        });
    }

    public Page<KnowledgeCardResponse> list(String keyword, String category, Boolean enabled, int page, int size) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        Page<KnowledgeCard> pageParam = new Page<>(page, size);

        LambdaQueryWrapper<KnowledgeCard> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeCard::getBaseCode, base.getBaseCode())
                .orderByDesc(KnowledgeCard::getUpdatedAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(KnowledgeCard::getTitle, keyword)
                    .or().like(KnowledgeCard::getCardCode, keyword)
                    .or().like(KnowledgeCard::getSubjectCode, keyword));
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(KnowledgeCard::getCategory, category);
        }
        if (enabled != null) {
            wrapper.eq(KnowledgeCard::getEnabled, enabled ? 1 : 0);
        }

        Page<KnowledgeCard> result = knowledgeCardMapper.selectPage(pageParam, wrapper);

        Page<KnowledgeCardResponse> response = new Page<>(page, size);
        response.setTotal(result.getTotal());
        response.setRecords(result.getRecords().stream().map(this::toResponse).toList());
        return response;
    }

    @Transactional
    public String reindex(String cardCode, String operator) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        getRequiredCard(base.getBaseCode(), cardCode);
        String jobId = knowledgeIndexJobService.createCardUpsertJob(base.getBaseCode(), cardCode, operator).getJobId();
        knowledgeCardMapper.updateIndexState(base.getBaseCode(), cardCode,
                KnowledgeIndexStatus.PENDING.getCode(), jobId, null, operator);
        return jobId;
    }

    public String rebuild(String operator) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        return knowledgeIndexJobService.createRebuildJob(base.getBaseCode(), operator).getJobId();
    }

    public KnowledgeMarkdownImportResponse importMarkdownDocuments(List<KnowledgeMarkdownDocument> documents,
                                                                  String defaultCategory,
                                                                  Boolean defaultEnabled,
                                                                  Boolean defaultTimeless,
                                                                  String codePrefix,
                                                                  String operator) {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        String resolvedCategory = resolveCategory(defaultCategory);
        boolean resolvedEnabled = Boolean.TRUE.equals(defaultEnabled);
        boolean resolvedTimeless = Boolean.TRUE.equals(defaultTimeless);

        List<KnowledgeMarkdownImportItemResponse> items = documents.stream()
                .map(document -> importSingleDocument(base, document, resolvedCategory,
                        resolvedEnabled, resolvedTimeless, codePrefix, operator))
                .toList();

        int successCount = (int) items.stream().filter(item -> "SUCCESS".equals(item.getStatus())).count();
        return KnowledgeMarkdownImportResponse.builder()
                .totalFiles(items.size())
                .successCount(successCount)
                .failedCount(items.size() - successCount)
                .items(items)
                .build();
    }

    /**
     * 预解析 Markdown 文档，生成卡片预览（不写入数据库）。
     * 仅校验文件格式 + 提取字段 + 检查 cardCode 唯一性。
     */
    public List<KnowledgeCardPreviewResponse> previewMarkdownDocuments(
            List<KnowledgeMarkdownDocument> documents,
            String defaultCategory,
            String codePrefix,
            Boolean defaultEnabled,
            Boolean defaultTimeless) {

        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        String resolvedCategory = resolveCategory(defaultCategory);
        boolean resolvedEnabled = Boolean.TRUE.equals(defaultEnabled);
        boolean resolvedTimeless = Boolean.TRUE.equals(defaultTimeless);

        return documents.stream()
                .map(doc -> parseDocumentToPreview(base, doc, resolvedCategory,
                        resolvedEnabled, resolvedTimeless, codePrefix))
                .toList();
    }

    /**
     * 批量写入用户确认后的卡片，每张卡片独立事务。
     */
    public KnowledgeMarkdownImportResponse batchCreateCards(
            List<KnowledgeCardUpsertRequest> requests, String operator) {

        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        List<KnowledgeMarkdownImportItemResponse> items = requests.stream()
                .map(req -> createSingleWithTx(base, req, operator))
                .toList();

        int successCount = (int) items.stream().filter(i -> "SUCCESS".equals(i.getStatus())).count();
        return KnowledgeMarkdownImportResponse.builder()
                .totalFiles(items.size())
                .successCount(successCount)
                .failedCount(items.size() - successCount)
                .items(items)
                .build();
    }

    // ==================== private ====================

    private KnowledgeCardPreviewResponse parseDocumentToPreview(KnowledgeBase base,
                                                                KnowledgeMarkdownDocument document,
                                                                String defaultCategory,
                                                                boolean defaultEnabled,
                                                                boolean defaultTimeless,
                                                                String codePrefix) {
        String fileName = document.getFileName();
        String contentMarkdown = document.getContentMarkdown() == null ? "" : document.getContentMarkdown().trim();

        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Markdown file name is required");
        }
        if (!isMarkdownFile(fileName)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Only .md or .markdown files are supported");
        }
        if (contentMarkdown.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Markdown content is empty: " + fileName);
        }

        String title = extractTitle(fileName, contentMarkdown);
        String cardCode = nextAvailableCardCode(base.getBaseCode(),
                buildCardCodeCandidate(fileName, title, codePrefix));

        return KnowledgeCardPreviewResponse.builder()
                .fileName(fileName)
                .cardCode(cardCode)
                .title(title)
                .category(defaultCategory)
                .subjectCode(null)
                .aliases(Collections.emptyList())
                .tags(List.of("imported", "markdown"))
                .contentMarkdown(contentMarkdown)
                .enabled(defaultEnabled)
                .timeless(defaultTimeless)
                .build();
    }

    private KnowledgeMarkdownImportItemResponse createSingleWithTx(KnowledgeBase base,
                                                                    KnowledgeCardUpsertRequest req,
                                                                    String operator) {
        try {
            return transactionTemplate.execute(status -> {
                KnowledgeCard existing = knowledgeCardMapper.selectByCardCode(base.getBaseCode(), req.getCardCode());
                if (existing != null) {
                    throw new BusinessException(ErrorCode.KNOWLEDGE_CARD_CODE_EXISTS);
                }

                KnowledgeCard card = new KnowledgeCard();
                applyRequest(card, base, req, operator);
                card.setIndexStatus(Boolean.TRUE.equals(req.getEnabled())
                        ? KnowledgeIndexStatus.PENDING.getCode()
                        : KnowledgeIndexStatus.DRAFT.getCode());
                knowledgeCardMapper.insert(card);

                String lastJobId = null;
                if (Boolean.TRUE.equals(req.getEnabled())) {
                    lastJobId = knowledgeIndexJobService.createCardUpsertJob(
                            base.getBaseCode(), card.getCardCode(), operator).getJobId();
                    knowledgeCardMapper.updateIndexState(base.getBaseCode(), card.getCardCode(),
                            KnowledgeIndexStatus.PENDING.getCode(), lastJobId, null, operator);
                }

                KnowledgeCard saved = knowledgeCardMapper.selectByCardCode(base.getBaseCode(), card.getCardCode());
                return KnowledgeMarkdownImportItemResponse.builder()
                        .cardCode(saved.getCardCode())
                        .title(saved.getTitle())
                        .category(saved.getCategory())
                        .enabled(saved.getEnabled() != null && saved.getEnabled() == 1)
                        .timeless(saved.getTimeless() != null && saved.getTimeless() == 1)
                        .indexStatus(saved.getIndexStatus())
                        .lastJobId(lastJobId)
                        .status("SUCCESS")
                        .message(Boolean.TRUE.equals(req.getEnabled())
                                ? "Imported and queued for indexing"
                                : "Imported as draft")
                        .build();
            });
        } catch (Exception e) {
            return KnowledgeMarkdownImportItemResponse.builder()
                    .cardCode(req.getCardCode())
                    .status("FAILED")
                    .message(e.getMessage())
                    .build();
        }
    }

    public KnowledgeCard getRequiredCard(String baseCode, String cardCode) {
        KnowledgeCard card = knowledgeCardMapper.selectByCardCode(baseCode, cardCode);
        if (card == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CARD_NOT_FOUND);
        }
        return card;
    }

    private void applyRequest(KnowledgeCard card, KnowledgeBase base, KnowledgeCardUpsertRequest request, String operator) {
        card.setBaseCode(base.getBaseCode());
        card.setCardCode(request.getCardCode());
        card.setTitle(request.getTitle().trim());
        card.setCategory(resolveCategory(request.getCategory()));
        card.setSubjectCode(request.getSubjectCode());
        card.setAliases(writeArray(request.getAliases()));
        card.setTags(writeArray(request.getTags()));
        card.setContentMarkdown(request.getContentMarkdown().trim());
        card.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        card.setTimeless(Boolean.TRUE.equals(request.getTimeless()) ? 1 : 0);
        card.setVersionTag(base.getCurrentVersionTag());
        if (card.getCreatedBy() == null) {
            card.setCreatedBy(operator);
        }
        card.setUpdatedBy(operator);
    }

    public KnowledgeCardResponse toResponse(KnowledgeCard card) {
        return KnowledgeCardResponse.builder()
                .baseCode(card.getBaseCode())
                .cardCode(card.getCardCode())
                .title(card.getTitle())
                .category(card.getCategory())
                .subjectCode(card.getSubjectCode())
                .aliases(readArray(card.getAliases()))
                .tags(readArray(card.getTags()))
                .contentMarkdown(card.getContentMarkdown())
                .enabled(card.getEnabled() != null && card.getEnabled() == 1)
                .timeless(card.getTimeless() != null && card.getTimeless() == 1)
                .versionTag(card.getVersionTag())
                .indexStatus(card.getIndexStatus())
                .lastJobId(card.getLastJobId())
                .createdBy(card.getCreatedBy())
                .updatedBy(card.getUpdatedBy())
                .indexedAt(card.getIndexedAt())
                .createdAt(card.getCreatedAt())
                .updatedAt(card.getUpdatedAt())
                .build();
    }

    public List<String> readArray(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSON array", e);
        }
    }

    private String writeArray(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyList() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON array", e);
        }
    }

    private KnowledgeMarkdownImportItemResponse importSingleDocument(KnowledgeBase base,
                                                                    KnowledgeMarkdownDocument document,
                                                                    String defaultCategory,
                                                                    boolean defaultEnabled,
                                                                    boolean defaultTimeless,
                                                                    String codePrefix,
                                                                    String operator) {
        try {
            return transactionTemplate.execute(status ->
                    importSingleDocumentInTx(base, document, defaultCategory,
                            defaultEnabled, defaultTimeless, codePrefix, operator));
        } catch (Exception e) {
            return KnowledgeMarkdownImportItemResponse.builder()
                    .fileName(document.getFileName())
                    .status("FAILED")
                    .message(e.getMessage())
                    .build();
        }
    }

    private KnowledgeMarkdownImportItemResponse importSingleDocumentInTx(KnowledgeBase base,
                                                                         KnowledgeMarkdownDocument document,
                                                                         String defaultCategory,
                                                                         boolean defaultEnabled,
                                                                         boolean defaultTimeless,
                                                                         String codePrefix,
                                                                         String operator) {
        String fileName = document.getFileName();
        String contentMarkdown = document.getContentMarkdown() == null ? "" : document.getContentMarkdown().trim();
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Markdown file name is required");
        }
        if (!isMarkdownFile(fileName)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Only .md or .markdown files are supported");
        }
        if (contentMarkdown.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Markdown content cannot be empty");
        }

        String title = extractTitle(fileName, contentMarkdown);
        String cardCode = nextAvailableCardCode(base.getBaseCode(),
                buildCardCodeCandidate(fileName, title, codePrefix));

        KnowledgeCard card = new KnowledgeCard();
        card.setBaseCode(base.getBaseCode());
        card.setCardCode(cardCode);
        card.setTitle(title);
        card.setCategory(defaultCategory);
        card.setSubjectCode(null);
        card.setAliases(writeArray(Collections.emptyList()));
        card.setTags(writeArray(List.of("imported", "markdown")));
        card.setContentMarkdown(contentMarkdown);
        card.setEnabled(defaultEnabled ? 1 : 0);
        card.setTimeless(defaultTimeless ? 1 : 0);
        card.setVersionTag(base.getCurrentVersionTag());
        card.setCreatedBy(operator);
        card.setUpdatedBy(operator);

        if (defaultEnabled) {
            card.setIndexStatus(KnowledgeIndexStatus.PENDING.getCode());
        } else {
            card.setIndexStatus(KnowledgeIndexStatus.DRAFT.getCode());
        }
        knowledgeCardMapper.insert(card);

        String lastJobId = null;
        if (defaultEnabled) {
            lastJobId = knowledgeIndexJobService.createCardUpsertJob(base.getBaseCode(), cardCode, operator).getJobId();
            knowledgeCardMapper.updateIndexState(base.getBaseCode(), cardCode,
                    KnowledgeIndexStatus.PENDING.getCode(), lastJobId, null, operator);
            card = knowledgeCardMapper.selectByCardCode(base.getBaseCode(), cardCode);
        }

        return KnowledgeMarkdownImportItemResponse.builder()
                .fileName(fileName)
                .cardCode(cardCode)
                .title(title)
                .category(defaultCategory)
                .enabled(defaultEnabled)
                .timeless(defaultTimeless)
                .indexStatus(card.getIndexStatus())
                .lastJobId(lastJobId)
                .status("SUCCESS")
                .message(defaultEnabled ? "Imported and queued for indexing" : "Imported as draft, metadata can be edited later")
                .build();
    }

    private String resolveCategory(String category) {
        String resolved = (category == null || category.isBlank()) ? KnowledgeCategory.MECHANIC.getCode() : category.trim().toUpperCase(Locale.ROOT);
        try {
            return KnowledgeCategory.fromCode(resolved).getCode();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Invalid category: " + resolved);
        }
    }

    private boolean isMarkdownFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private String extractTitle(String fileName, String contentMarkdown) {
        for (String rawLine : contentMarkdown.split("\\r?\\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(line);
            if (matcher.matches()) {
                return truncate(matcher.group(1).trim(), 255);
            }
            if (!line.isBlank()) {
                return truncate(stripMarkdownDecoration(line), 255);
            }
        }
        return truncate(stripExtension(fileName), 255);
    }

    private String stripMarkdownDecoration(String text) {
        return text.replaceAll("^[>*\\-\\s]+", "").trim();
    }

    private String buildCardCodeCandidate(String fileName, String title, String codePrefix) {
        Set<String> parts = new LinkedHashSet<>();
        String normalizedPrefix = normalizeCodePart(codePrefix);
        if (!normalizedPrefix.isBlank()) {
            parts.add(normalizedPrefix);
        }

        String normalizedFileName = normalizeCodePart(stripExtension(fileName));
        if (!normalizedFileName.isBlank()) {
            parts.add(normalizedFileName);
        } else {
            String normalizedTitle = normalizeCodePart(title);
            if (!normalizedTitle.isBlank()) {
                parts.add(normalizedTitle);
            }
        }

        String candidate = String.join("-", parts);
        if (candidate.isBlank()) {
            candidate = "md-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return truncate(candidate, 64);
    }

    private String nextAvailableCardCode(String baseCode, String candidate) {
        String normalizedCandidate = candidate;
        int suffix = 2;
        while (knowledgeCardMapper.selectByCardCode(baseCode, normalizedCandidate) != null) {
            String suffixText = "-" + suffix++;
            normalizedCandidate = truncate(candidate, 64 - suffixText.length()) + suffixText;
        }
        return normalizedCandidate;
    }

    private String normalizeCodePart(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "")
                .replaceAll("-{2,}", "-");
        return truncate(normalized, 40);
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String name = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
