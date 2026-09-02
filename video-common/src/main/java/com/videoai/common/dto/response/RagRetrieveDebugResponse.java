package com.videoai.common.dto.response;

import com.videoai.common.rag.RetrievalHit;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagRetrieveDebugResponse {

    private String queryText;
    private String expandedQuery;
    private String versionTag;
    private String collectionName;
    private Integer topK;
    private Integer finalTopK;
    private Integer maxChunksPerCard;
    private Integer maxContextChars;
    private Double minScore;
    private Boolean legendPcGameplayFilterEnabled;
    private Boolean legendAliasEnhancementEnabled;
    private Boolean embeddingHeadingPathEnabled;
    private Boolean hybridRetrievalEnabled;
    private Boolean hybridConditionalRescueEnabled;
    private Double hybridConditionalRescueMinDenseScore;
    private Integer hybridConditionalRescueMaxChunks;
    private Boolean hybridLexicalUnionEnabled;
    private Integer lexicalTopK;
    private Integer rrfK;
    private Double denseRrfWeight;
    private Double lexicalRrfWeight;
    private Integer hitCount;
    private Integer rawCandidateCount;
    private Integer lexicalCandidateCount;
    private Integer fusedCandidateCount;
    private Integer scorePassedCount;
    private Integer diversifiedCount;
    private Integer selectedCount;
    private Integer contextChars;
    private Integer latencyMs;
    private String contextPreview;
    private String promptPreview;
    private List<RetrievalHit> rawCandidates;
    private List<RetrievalHit> lexicalCandidates;
    private List<RetrievalHit> fusedCandidates;
    private List<RetrievalHit> scorePassedCandidates;
    private List<RetrievalHit> diversifiedCandidates;
    private List<RetrievalHit> selectedCandidates;
    private List<RetrievalHit> hits;
}
