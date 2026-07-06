package com.videoai.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.rag.RagContext;
import com.videoai.common.rag.RetrievalHit;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorSearchResult;
import com.videoai.infra.rag.vector.EmbeddingProvider;
import com.videoai.infra.rag.vector.VectorStoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStoreClient vectorStoreClient;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public RagContext retrieve(String query) {
        long start = System.currentTimeMillis();
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        String expandedQuery = expandQuery(base, query);
        List<Float> vector = embeddingProvider.embed(expandedQuery);
        String filter = buildFilterExpression(base.getBaseCode(), base.getCurrentVersionTag());
        List<VectorSearchResult> searchResults = vectorStoreClient.search(vector, ragProperties.getTopK(), filter);

        List<RetrievalHit> hits = new ArrayList<>();
        for (VectorSearchResult result : searchResults) {
            if (result.getScore() < ragProperties.getMinScore()) {
                continue;
            }
            Map<String, Object> fields = result.getFields();
            hits.add(RetrievalHit.builder()
                    .vectorId(result.getId())
                    .cardCode(stringValue(fields.get("card_code")))
                    .title(stringValue(fields.get("title")))
                    .category(stringValue(fields.get("category")))
                    .headingPath(stringValue(fields.get("heading_path")))
                    .contentText(stringValue(fields.get("content_text")))
                    .score(result.getScore())
                    .build());
            if (hits.size() >= ragProperties.getFinalTopK()) {
                break;
            }
        }

        StringBuilder contextBuilder = new StringBuilder();
        for (RetrievalHit hit : hits) {
            String block = "### " + hit.getTitle() + "\n"
                    + "- 分类: " + hit.getCategory() + "\n"
                    + "- 位置: " + hit.getHeadingPath() + "\n"
                    + hit.getContentText() + "\n\n";
            if (contextBuilder.length() + block.length() > ragProperties.getMaxContextChars()) {
                break;
            }
            contextBuilder.append(block);
        }

        return RagContext.builder()
                .baseCode(base.getBaseCode())
                .versionTag(base.getCurrentVersionTag())
                .queryText(expandedQuery)
                .hits(hits)
                .contextText(contextBuilder.toString().trim())
                .status("HIT")
                .latencyMs((int) (System.currentTimeMillis() - start))
                .build();
    }

    /**
     * 扩展用户查询。对于中文查询，保持原有的关键词匹配和中文提示词。
     * 对于纯英文知识库场景，依赖 text-embedding-v3 的跨语言能力。
     */
    /**
     * Query expansion: 给用户查询附加 Apex 领域术语作为语义引导。
     * 不做关键词匹配（跨语言场景下字符串匹配低效），纯靠 embedding 跨语言语义检索。
     */
    private String expandQuery(KnowledgeBase base, String query) {
        String safeQuery = (query == null || query.isBlank())
                ? "Analyze this Apex Legends gameplay video."
                : query.trim();

        return safeQuery + "\nKey Apex terminology: Legend abilities (tactical, passive, ultimate), "
                + "weapon stats (damage, DPS, attachments), map POI names and rotations, "
                + "team compositions and fight decisions, item and ability timing.";
    }

    private String buildFilterExpression(String baseCode, String versionTag) {
        return "kb_code == \"" + baseCode + "\" and enabled == 1 and (timeless == 1 or version_tag == \"" + versionTag + "\")";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> readAliases(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse aliases JSON, falling back to empty list: {}", raw, e);
            return Collections.emptyList();
        }
    }
}
