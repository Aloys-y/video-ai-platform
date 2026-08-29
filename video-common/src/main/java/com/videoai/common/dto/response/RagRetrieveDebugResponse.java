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
    private Integer hitCount;
    private Integer contextChars;
    private Integer latencyMs;
    private String contextPreview;
    private String promptPreview;
    private List<RetrievalHit> hits;
}
