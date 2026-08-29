package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.common.dto.response.LegendCardAuditItem;
import com.videoai.common.dto.response.LegendKnowledgeAuditResponse;
import com.videoai.common.enums.KnowledgeIndexStatus;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.rag.config.MilvusProperties;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import com.videoai.infra.rag.vector.VectorStoreClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class LegendKnowledgeAuditService {

    private static final String CATEGORY_LEGEND = "LEGEND";

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final VectorStoreClient vectorStoreClient;
    private final MilvusProperties milvusProperties;
    private final OpenAiEmbeddingProperties embeddingProperties;

    public LegendKnowledgeAuditResponse audit() {
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        List<KnowledgeCard> cards = knowledgeCardMapper.selectByCategory(base.getBaseCode(), CATEGORY_LEGEND);
        String vectorFilter = "kb_code == \"" + base.getBaseCode() + "\" and category == \"" + CATEGORY_LEGEND + "\"";
        Map<String, Long> vectorCounts = vectorStoreClient.countByCard(vectorFilter);

        List<LegendCardAuditItem> items = new ArrayList<>(cards.size());
        int enabledCards = 0;
        int mobileCards = 0;
        int gameplayCards = 0;
        int mysqlChunkCount = 0;
        long milvusVectorCount = 0L;
        int matchedCards = 0;

        for (KnowledgeCard card : cards) {
            boolean enabled = Integer.valueOf(1).equals(card.getEnabled());
            String platform = classifyPlatform(card);
            String knowledgeType = classifyKnowledgeType(card);
            int mysqlChunks = knowledgeChunkMapper.selectByCardCode(base.getBaseCode(), card.getCardCode()).size();
            long vectors = vectorCounts.getOrDefault(card.getCardCode(), 0L);
            String coverageStatus = coverageStatus(card, enabled, mysqlChunks, vectors);

            if (enabled) enabledCards++;
            if ("MOBILE".equals(platform)) mobileCards++;
            if ("GAMEPLAY".equals(knowledgeType)) gameplayCards++;
            mysqlChunkCount += mysqlChunks;
            milvusVectorCount += vectors;
            if (coverageStatus.startsWith("MATCHED")) matchedCards++;

            String content = card.getContentMarkdown() == null ? "" : card.getContentMarkdown();
            items.add(LegendCardAuditItem.builder()
                    .cardCode(card.getCardCode())
                    .title(card.getTitle())
                    .enabled(enabled)
                    .indexStatus(card.getIndexStatus())
                    .versionTag(card.getVersionTag())
                    .indexedAt(card.getIndexedAt())
                    .platform(platform)
                    .knowledgeType(knowledgeType)
                    .contentSha256(sha256(content))
                    .contentChars(content.length())
                    .mysqlChunkCount(mysqlChunks)
                    .milvusVectorCount(vectors)
                    .coverageStatus(coverageStatus)
                    .build());
        }

        Set<String> mysqlCardCodes = new HashSet<>();
        cards.forEach(card -> mysqlCardCodes.add(card.getCardCode()));
        Map<String, Long> orphanVectorsByCard = new TreeMap<>();
        vectorCounts.forEach((cardCode, count) -> {
            if (!mysqlCardCodes.contains(cardCode)) {
                orphanVectorsByCard.put(cardCode, count);
            }
        });
        long orphanVectors = orphanVectorsByCard.values().stream().mapToLong(Long::longValue).sum();
        milvusVectorCount += orphanVectors;

        return LegendKnowledgeAuditResponse.builder()
                .baseCode(base.getBaseCode())
                .versionTag(base.getCurrentVersionTag())
                .collectionName(milvusProperties.getCollection())
                .embeddingProvider(embeddingProperties.getProvider())
                .embeddingModel(embeddingProperties.getModel())
                .embeddingDimension(embeddingProperties.getDimension())
                .embeddingTextType(embeddingProperties.getDashscope().getTextType())
                .generatedAt(LocalDateTime.now())
                .totalCards(cards.size())
                .enabledCards(enabledCards)
                .mobileCards(mobileCards)
                .gameplayCards(gameplayCards)
                .mysqlChunkCount(mysqlChunkCount)
                .milvusVectorCount(milvusVectorCount)
                .orphanVectorCards(orphanVectorsByCard.size())
                .orphanVectorCount(orphanVectors)
                .orphanVectorsByCard(orphanVectorsByCard)
                .matchedCards(matchedCards)
                .mismatchCards(cards.size() - matchedCards + orphanVectorsByCard.size())
                .cards(items)
                .build();
    }

    private String coverageStatus(KnowledgeCard card, boolean enabled, int mysqlChunks, long vectors) {
        if (!enabled && mysqlChunks == 0 && vectors == 0) {
            return "MATCHED_DISABLED";
        }
        if (enabled
                && KnowledgeIndexStatus.INDEXED.getCode().equals(card.getIndexStatus())
                && mysqlChunks > 0
                && mysqlChunks == vectors) {
            return "MATCHED_INDEXED";
        }
        if (mysqlChunks == 0 && vectors == 0) {
            return "MISSING_BOTH";
        }
        if (mysqlChunks > vectors) {
            return "MILVUS_MISSING";
        }
        if (vectors > mysqlChunks) {
            return "MILVUS_EXTRA";
        }
        return "STATUS_MISMATCH";
    }

    private String classifyPlatform(KnowledgeCard card) {
        String value = normalizedIdentity(card);
        return value.contains("mobile") || "fade".equals(card.getCardCode()) || "rhapsody".equals(card.getCardCode())
                ? "MOBILE" : "PC";
    }

    private String classifyKnowledgeType(KnowledgeCard card) {
        String value = normalizedIdentity(card);
        if (value.contains("character")) {
            return "LORE";
        }
        String cardCode = card.getCardCode() == null ? "" : card.getCardCode().toLowerCase(Locale.ROOT);
        if (cardCode.matches(".*[-/](id|ru)$")
                || cardCode.startsWith("how-to-play-guide-for-apex-legends")
                || "legend".equals(cardCode)) {
            return "AUXILIARY";
        }
        return "GAMEPLAY";
    }

    private String normalizedIdentity(KnowledgeCard card) {
        return ((card.getTitle() == null ? "" : card.getTitle()) + " "
                + (card.getCardCode() == null ? "" : card.getCardCode())).toLowerCase(Locale.ROOT);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
