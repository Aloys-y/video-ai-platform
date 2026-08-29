package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.rag.config.MilvusProperties;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorRecord;
import com.videoai.infra.rag.vector.VectorStoreClient;
import com.videoai.rag.model.ChunkedSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LegendShadowIndexService {

    private static final String BASELINE_COLLECTION = "apex_knowledge_chunk";

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final KnowledgeChunkingService knowledgeChunkingService;
    private final KnowledgeIndexingService knowledgeIndexingService;
    private final VectorStoreClient vectorStoreClient;
    private final RagProperties ragProperties;
    private final MilvusProperties milvusProperties;

    public Map<String, Object> build() {
        assertShadowBuildIsSafe();
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        Set<String> excluded = Set.copyOf(ragProperties.getLegendExcludedCardCodes());
        List<KnowledgeCard> cards = knowledgeCardMapper.selectByCategory(base.getBaseCode(), "LEGEND").stream()
                .filter(card -> Integer.valueOf(1).equals(card.getEnabled()))
                .filter(card -> !excluded.contains(card.getCardCode()))
                .toList();

        vectorStoreClient.ensureCollection();
        List<Integer> lengths = new ArrayList<>();
        int vectorCount = 0;
        for (KnowledgeCard card : cards) {
            List<ChunkedSegment> segments = knowledgeChunkingService.chunkMarkdown(
                    card.getTitle(), card.getContentMarkdown());
            List<VectorRecord> records = knowledgeIndexingService.buildVectorRecords(card, segments);
            if (!records.isEmpty()) {
                vectorStoreClient.upsert(records);
            }
            vectorCount += records.size();
            segments.forEach(segment -> lengths.add(segment.getContentText().length()));
        }

        lengths.sort(Comparator.naturalOrder());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collectionName", milvusProperties.getCollection());
        result.put("cardCount", cards.size());
        result.put("vectorCount", vectorCount);
        result.put("minChars", lengths.isEmpty() ? 0 : lengths.get(0));
        result.put("p50Chars", percentile(lengths, 0.50));
        result.put("p95Chars", percentile(lengths, 0.95));
        result.put("maxChars", lengths.isEmpty() ? 0 : lengths.get(lengths.size() - 1));
        result.put("belowMinCount", lengths.stream().filter(value -> value < ragProperties.getChunkMinChars()).count());
        result.put("aboveMaxCount", lengths.stream().filter(value -> value > ragProperties.getChunkMaxChars()).count());
        return result;
    }

    public Map<String, Object> promote() {
        assertShadowBuildIsSafe();
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        List<KnowledgeCard> cards = knowledgeCardMapper.selectByCategory(base.getBaseCode(), "LEGEND").stream()
                .filter(card -> Integer.valueOf(1).equals(card.getEnabled()))
                .toList();
        Map<String, Long> existingCounts = vectorStoreClient.countByCard(
                "kb_code == \"" + base.getBaseCode() + "\" and category == \"LEGEND\"");

        int embeddedVectors = 0;
        int persistedChunks = 0;
        String promotionId = "l03-shadow-promote-" + LocalDateTime.now();
        for (KnowledgeCard card : cards) {
            List<ChunkedSegment> segments = knowledgeChunkingService.chunkMarkdown(
                    card.getTitle(), card.getContentMarkdown());
            List<VectorRecord> metadataRecords = knowledgeIndexingService.buildMetadataRecords(card, segments);
            long existingCount = existingCounts.getOrDefault(card.getCardCode(), 0L);
            if (existingCount != segments.size()) {
                List<VectorRecord> embeddedRecords = knowledgeIndexingService.buildVectorRecords(card, segments);
                vectorStoreClient.upsert(embeddedRecords);
                metadataRecords = embeddedRecords;
                embeddedVectors += embeddedRecords.size();
            }
            persistedChunks += knowledgeIndexingService.persistToDatabase(
                    base.getBaseCode(), card, segments, metadataRecords, promotionId, false);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collectionName", milvusProperties.getCollection());
        result.put("cardCount", cards.size());
        result.put("persistedChunkCount", persistedChunks);
        result.put("newlyEmbeddedVectorCount", embeddedVectors);
        result.put("baselineCollectionPreserved", BASELINE_COLLECTION);
        return result;
    }

    private void assertShadowBuildIsSafe() {
        String collection = milvusProperties.getCollection();
        if (!ragProperties.isShadowIndexBuildEnabled()) {
            throw new IllegalStateException("Shadow index build is disabled");
        }
        if (collection == null || collection.isBlank()
                || BASELINE_COLLECTION.equals(collection)
                || !collection.startsWith(BASELINE_COLLECTION + "_l03_")) {
            throw new IllegalStateException("Refusing shadow build for unsafe collection: " + collection);
        }
    }

    private int percentile(List<Integer> values, double quantile) {
        if (values.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(quantile * values.size()) - 1);
        return values.get(index);
    }
}
