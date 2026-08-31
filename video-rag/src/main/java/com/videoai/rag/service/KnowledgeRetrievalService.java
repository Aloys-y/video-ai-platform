package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.rag.RagContext;
import com.videoai.common.rag.RetrievalHit;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorSearchResult;
import com.videoai.infra.rag.vector.EmbeddingProvider;
import com.videoai.infra.rag.vector.VectorStoreClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStoreClient vectorStoreClient;
    private final RagProperties ragProperties;
    private final LegendQueryEnhancementService legendQueryEnhancementService;

    public RagContext retrieve(String query) {
        long start = System.currentTimeMillis();
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        String expandedQuery = prepareQuery(query);
        List<Float> vector = embeddingProvider.embedQuery(expandedQuery);
        String filter = buildFilterExpression(base.getBaseCode(), base.getCurrentVersionTag());
        List<VectorSearchResult> searchResults = vectorStoreClient.search(vector, ragProperties.getTopK(), filter);

        List<RetrievalHit> hits = new ArrayList<>();
        Map<String, Integer> chunksPerCard = new HashMap<>();
        for (VectorSearchResult result : searchResults) {
            if (result.getScore() < ragProperties.getMinScore()) {
                continue;
            }
            Map<String, Object> fields = result.getFields();
            String cardCode = stringValue(fields.get("card_code"));
            int cardHitCount = chunksPerCard.getOrDefault(cardCode, 0);
            if (cardHitCount >= Math.max(1, ragProperties.getMaxChunksPerCard())) {
                continue;
            }
            hits.add(RetrievalHit.builder()
                    .vectorId(result.getId())
                    .cardCode(cardCode)
                    .title(stringValue(fields.get("title")))
                    .category(stringValue(fields.get("category")))
                    .headingPath(stringValue(fields.get("heading_path")))
                    .contentText(stringValue(fields.get("content_text")))
                    .score(result.getScore())
                    .build());
            chunksPerCard.put(cardCode, cardHitCount + 1);
            if (hits.size() >= ragProperties.getFinalTopK()) {
                break;
            }
        }

        StringBuilder contextBuilder = new StringBuilder();
        List<RetrievalHit> contextHits = new ArrayList<>();
        for (RetrievalHit hit : hits) {
            String block = "### " + hit.getTitle() + "\n"
                    + "- 分类: " + hit.getCategory() + "\n"
                    + "- 位置: " + hit.getHeadingPath() + "\n"
                    + hit.getContentText() + "\n\n";
            if (contextBuilder.length() + block.length() > ragProperties.getMaxContextChars()) {
                continue;
            }
            contextBuilder.append(block);
            contextHits.add(hit);
        }

        return RagContext.builder()
                .baseCode(base.getBaseCode())
                .versionTag(base.getCurrentVersionTag())
                .queryText(expandedQuery)
                .hits(contextHits)
                .contextText(contextBuilder.toString().trim())
                .status(contextHits.isEmpty() ? "MISS" : "HIT")
                .latencyMs((int) (System.currentTimeMillis() - start))
                .build();
    }

    /**
     * Query expansion: 给用户查询附加 Apex 领域术语作为语义引导。
     * 不做关键词匹配（跨语言场景下字符串匹配低效），纯靠 embedding 跨语言语义检索。
     */
    private String prepareQuery(String query) {
        String safeQuery = (query == null || query.isBlank())
                ? "Analyze this Apex Legends gameplay video."
                : query.trim();
        String entityEnhancedQuery = legendQueryEnhancementService.enhance(safeQuery);

        if (!ragProperties.isQueryExpansionEnabled()) {
            return entityEnhancedQuery;
        }

        return entityEnhancedQuery + "\nKey Apex terminology: Legend abilities (tactical, passive, ultimate), "
                + "weapon stats (damage, DPS, attachments), map POI names and rotations, "
                + "team compositions and fight decisions, item and ability timing.";
    }

    private String buildFilterExpression(String baseCode, String versionTag) {
        String filter = "kb_code == \"" + escapeFilterValue(baseCode)
                + "\" and enabled == 1 and (timeless == 1 or version_tag == \""
                + escapeFilterValue(versionTag) + "\")";
        if (!ragProperties.isLegendPcGameplayFilterEnabled()) {
            return filter;
        }

        return filter + " and category == \"LEGEND\"";
    }

    private String escapeFilterValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
