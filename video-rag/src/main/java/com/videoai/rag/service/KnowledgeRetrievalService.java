package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.rag.RagContext;
import com.videoai.common.rag.RetrievalHit;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.model.VectorSearchResult;
import com.videoai.infra.rag.vector.EmbeddingProvider;
import com.videoai.infra.rag.vector.VectorStoreClient;
import com.videoai.rag.model.LexicalSearchResult;
import com.videoai.rag.model.RagRetrievalTrace;
import com.videoai.rag.model.RerankResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeRetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStoreClient vectorStoreClient;
    private final RagProperties ragProperties;
    private final LegendQueryEnhancementService legendQueryEnhancementService;
    private final Bm25RetrievalService bm25RetrievalService;
    private final RerankService rerankService;

    public RagContext retrieve(String query) {
        return retrieveTrace(query).getContext();
    }

    /**
     * 执行一次真实检索并保留每层候选，供离线评测分析阈值和截断损失。
     */
    public RagRetrievalTrace retrieveTrace(String query) {
        long start = System.currentTimeMillis();
        KnowledgeBase base = knowledgeBaseService.getRequiredBase();
        String expandedQuery = prepareQuery(query);
        List<Float> vector = embeddingProvider.embedQuery(expandedQuery);
        String filter = buildFilterExpression(base.getBaseCode(), base.getCurrentVersionTag());
        List<VectorSearchResult> searchResults = vectorStoreClient.search(vector, ragProperties.getTopK(), filter);

        List<RetrievalHit> rawCandidates = new ArrayList<>();
        for (int i = 0; i < searchResults.size(); i++) {
            rawCandidates.add(toRetrievalHit(searchResults.get(i), i + 1));
        }

        List<RetrievalHit> lexicalCandidates = List.of();
        List<RetrievalHit> fusedCandidates = rawCandidates;
        List<RetrievalHit> rerankedCandidates = List.of();
        List<RetrievalHit> scorePassedCandidates;
        boolean rerankApplied = false;
        boolean rerankFallback = false;
        int rerankLatencyMs = 0;
        String rerankFailureReason = null;

        if (ragProperties.isRerankEnabled() && !rawCandidates.isEmpty()) {
            long rerankStart = System.currentTimeMillis();
            try {
                // 实验链路固定为纯向量 TopK → Rerank。所有原始 chunk 都进入重排，不按
                // HeadingPath 去重，也不先过 dense minScore，避免正确内容在重排前被截断。
                String rerankQuery = rerankQuery(query);
                rerankedCandidates = applyRerank(
                        rawCandidates, rerankService.rerank(rerankQuery, rawCandidates));
                rerankApplied = true;
                scorePassedCandidates = rerankedCandidates.stream()
                        .filter(hit -> hit.getRerankScore() != null
                                && hit.getRerankScore() >= ragProperties.getRerankMinScore())
                        .toList();
            } catch (RuntimeException e) {
                rerankFallback = true;
                rerankFailureReason = e.getMessage();
                if (!ragProperties.isRerankFailOpen()) {
                    throw e;
                }
                log.warn("Reranker failed; falling back to dense retrieval: {}", e.getMessage());
                scorePassedCandidates = denseScorePassed(rawCandidates);
            } finally {
                rerankLatencyMs = (int) (System.currentTimeMillis() - rerankStart);
            }
        } else if (ragProperties.isHybridRetrievalEnabled()) {
            List<RetrievalHit> densePassedCandidates = rawCandidates.stream()
                    .filter(hit -> hit.getScore() >= ragProperties.getMinScore())
                    .toList();
            if (ragProperties.isHybridConditionalRescueEnabled()) {
                int denseAvailable = Math.min(
                        diversifyCandidates(densePassedCandidates).size(),
                        Math.max(1, ragProperties.getFinalTopK()));
                boolean rescueNeeded = !densePassedCandidates.isEmpty()
                        && denseAvailable < Math.max(1, ragProperties.getFinalTopK());
                if (rescueNeeded) {
                    List<LexicalSearchResult> lexicalResults = bm25RetrievalService.search(
                            expandedQuery, base.getBaseCode(), base.getCurrentVersionTag(),
                            Math.max(1, ragProperties.getLexicalTopK()));
                    lexicalCandidates = toLexicalHits(lexicalResults);
                    fusedCandidates = appendLexicalRescueCandidates(
                            densePassedCandidates, rawCandidates, lexicalCandidates);
                    scorePassedCandidates = fusedCandidates;
                } else {
                    scorePassedCandidates = densePassedCandidates;
                }
            } else {
                List<LexicalSearchResult> lexicalResults = bm25RetrievalService.search(
                        expandedQuery, base.getBaseCode(), base.getCurrentVersionTag(),
                        Math.max(1, ragProperties.getLexicalTopK()));
                lexicalCandidates = toLexicalHits(lexicalResults);
                fusedCandidates = fuseCandidates(rawCandidates, lexicalCandidates);

                // BM25 对任何非空查询都可能给出结果，不能单独承担“是否属于知识域”的判断。
                // 先要求本次查询至少有一个向量候选通过语义阈值，再允许词法候选参与融合。
                boolean denseGatePassed = !densePassedCandidates.isEmpty();
                scorePassedCandidates = denseGatePassed
                        ? fusedCandidates.stream()
                        .filter(hit -> hit.getDenseScore() != null
                                && hit.getDenseScore() >= ragProperties.getMinScore()
                                || ragProperties.isHybridLexicalUnionEnabled()
                                && hit.getLexicalScore() != null)
                        .toList()
                        : List.of();
            }
        } else {
            scorePassedCandidates = denseScorePassed(rawCandidates);
        }

        List<RetrievalHit> diversifiedCandidates = diversifyCandidates(scorePassedCandidates);

        List<RetrievalHit> selectedCandidates = diversifiedCandidates.stream()
                .limit(Math.max(1, ragProperties.getFinalTopK()))
                .toList();

        StringBuilder contextBuilder = new StringBuilder();
        List<RetrievalHit> contextHits = new ArrayList<>();
        for (RetrievalHit hit : selectedCandidates) {
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

        RagContext context = RagContext.builder()
                .baseCode(base.getBaseCode())
                .versionTag(base.getCurrentVersionTag())
                .queryText(expandedQuery)
                .hits(contextHits)
                .contextText(contextBuilder.toString().trim())
                .status(contextHits.isEmpty() ? "MISS" : "HIT")
                .latencyMs((int) (System.currentTimeMillis() - start))
                .build();

        return RagRetrievalTrace.builder()
                .context(context)
                .rawCandidates(rawCandidates)
                .lexicalCandidates(lexicalCandidates)
                .fusedCandidates(fusedCandidates)
                .rerankedCandidates(rerankedCandidates)
                .scorePassedCandidates(scorePassedCandidates)
                .diversifiedCandidates(List.copyOf(diversifiedCandidates))
                .selectedCandidates(selectedCandidates)
                .rerankApplied(rerankApplied)
                .rerankFallback(rerankFallback)
                .rerankLatencyMs(rerankLatencyMs)
                .rerankFailureReason(rerankFailureReason)
                .build();
    }

    private List<RetrievalHit> applyRerank(List<RetrievalHit> candidates,
                                            List<RerankResult> results) {
        if (results.size() != candidates.size()) {
            throw new IllegalStateException("Reranker result count does not match candidates");
        }

        List<RetrievalHit> reranked = new ArrayList<>(results.size());
        for (RerankResult result : results) {
            if (result.index() < 0 || result.index() >= candidates.size()) {
                throw new IllegalStateException("Reranker result index is out of range");
            }
            RetrievalHit hit = candidates.get(result.index());
            hit.setRerankScore(result.score());
            reranked.add(hit);
        }
        reranked.sort((left, right) -> Double.compare(
                right.getRerankScore(), left.getRerankScore()));
        for (int i = 0; i < reranked.size(); i++) {
            reranked.get(i).setRerankRank(i + 1);
        }
        return List.copyOf(reranked);
    }

    private List<RetrievalHit> denseScorePassed(List<RetrievalHit> candidates) {
        return candidates.stream()
                .filter(hit -> hit.getScore() >= ragProperties.getMinScore())
                .toList();
    }

    private String rerankQuery(String query) {
        return query == null || query.isBlank()
                ? "Analyze this Apex Legends gameplay video."
                : query.trim();
    }

    private List<RetrievalHit> diversifyCandidates(List<RetrievalHit> candidates) {
        List<RetrievalHit> diversifiedCandidates = new ArrayList<>();
        Map<String, Integer> chunksPerCard = new HashMap<>();
        for (RetrievalHit hit : candidates) {
            String cardCode = hit.getCardCode();
            int cardHitCount = chunksPerCard.getOrDefault(cardCode, 0);
            if (cardHitCount >= Math.max(1, ragProperties.getMaxChunksPerCard())) {
                continue;
            }
            diversifiedCandidates.add(hit);
            chunksPerCard.put(cardCode, cardHitCount + 1);
        }
        return diversifiedCandidates;
    }

    private List<RetrievalHit> appendLexicalRescueCandidates(
            List<RetrievalHit> densePassedCandidates,
            List<RetrievalHit> rawCandidates,
            List<RetrievalHit> lexicalCandidates) {
        List<RetrievalHit> combined = new ArrayList<>(densePassedCandidates);
        Map<String, RetrievalHit> rawByVectorId = new HashMap<>();
        rawCandidates.forEach(hit -> rawByVectorId.put(hit.getVectorId(), hit));
        Map<String, Boolean> included = new HashMap<>();
        densePassedCandidates.forEach(hit -> included.put(hit.getVectorId(), true));

        List<RetrievalHit> denseSelected = diversifyCandidates(densePassedCandidates).stream()
                .limit(Math.max(1, ragProperties.getFinalTopK()))
                .toList();
        Map<String, Integer> selectedChunksPerCard = new HashMap<>();
        denseSelected.forEach(hit -> selectedChunksPerCard.merge(hit.getCardCode(), 1, Integer::sum));
        int availableSlots = Math.max(0, Math.max(1, ragProperties.getFinalTopK()) - denseSelected.size());
        int rescueLimit = Math.min(
                availableSlots,
                Math.max(1, ragProperties.getHybridConditionalRescueMaxChunks()));
        int rescued = 0;

        for (RetrievalHit lexical : lexicalCandidates) {
            if (rescued >= rescueLimit) {
                break;
            }
            if (included.containsKey(lexical.getVectorId())) {
                continue;
            }
            RetrievalHit rescue = rawByVectorId.get(lexical.getVectorId());
            if (rescue == null || rescue.getDenseScore() == null
                    || rescue.getDenseScore()
                    < ragProperties.getHybridConditionalRescueMinDenseScore()) {
                continue;
            }
            int cardCount = selectedChunksPerCard.getOrDefault(rescue.getCardCode(), 0);
            if (cardCount >= Math.max(1, ragProperties.getMaxChunksPerCard())) {
                continue;
            }
            rescue.setLexicalScore(lexical.getLexicalScore());
            rescue.setLexicalRank(lexical.getLexicalRank());
            combined.add(rescue);
            included.put(rescue.getVectorId(), true);
            selectedChunksPerCard.put(rescue.getCardCode(), cardCount + 1);
            rescued++;
        }
        return List.copyOf(combined);
    }

    private RetrievalHit toRetrievalHit(VectorSearchResult result, int rank) {
        Map<String, Object> fields = result.getFields();
        return RetrievalHit.builder()
                .vectorId(result.getId())
                .cardCode(stringValue(fields.get("card_code")))
                .title(stringValue(fields.get("title")))
                .category(stringValue(fields.get("category")))
                .headingPath(stringValue(fields.get("heading_path")))
                .contentText(stringValue(fields.get("content_text")))
                .score(result.getScore())
                .denseScore(result.getScore())
                .denseRank(rank)
                .build();
    }

    private List<RetrievalHit> toLexicalHits(List<LexicalSearchResult> results) {
        List<RetrievalHit> hits = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            LexicalSearchResult result = results.get(i);
            var chunk = result.chunk();
            hits.add(RetrievalHit.builder()
                    .vectorId(chunk.getVectorId())
                    .cardCode(chunk.getCardCode())
                    .title(chunk.getTitle())
                    .category(chunk.getCategory())
                    .headingPath(chunk.getHeadingPath())
                    .contentText(chunk.getContentText())
                    .score(0D)
                    .lexicalScore(result.score())
                    .lexicalRank(i + 1)
                    .build());
        }
        return List.copyOf(hits);
    }

    private List<RetrievalHit> fuseCandidates(List<RetrievalHit> denseCandidates,
                                               List<RetrievalHit> lexicalCandidates) {
        Map<String, RetrievalHit> byVectorId = new LinkedHashMap<>();
        denseCandidates.forEach(hit -> byVectorId.put(hit.getVectorId(), hit));
        for (RetrievalHit lexical : lexicalCandidates) {
            RetrievalHit existing = byVectorId.get(lexical.getVectorId());
            if (existing == null) {
                byVectorId.put(lexical.getVectorId(), lexical);
            } else {
                existing.setLexicalScore(lexical.getLexicalScore());
                existing.setLexicalRank(lexical.getLexicalRank());
            }
        }

        int rrfK = Math.max(1, ragProperties.getRrfK());
        for (RetrievalHit hit : byVectorId.values()) {
            double fusionScore = 0D;
            if (hit.getDenseRank() != null) {
                fusionScore += ragProperties.getDenseRrfWeight() / (rrfK + hit.getDenseRank());
            }
            if (hit.getLexicalRank() != null) {
                fusionScore += ragProperties.getLexicalRrfWeight() / (rrfK + hit.getLexicalRank());
            }
            hit.setFusionScore(fusionScore);
        }

        return byVectorId.values().stream()
                .sorted((left, right) -> {
                    int fusionOrder = Double.compare(right.getFusionScore(), left.getFusionScore());
                    if (fusionOrder != 0) {
                        return fusionOrder;
                    }
                    return Double.compare(right.getScore(), left.getScore());
                })
                .toList();
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
