package com.videoai.common.dto.response;

import com.videoai.common.rag.RetrievalHit;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagRetrieveDebugResponse {

    private String expandedQuery;
    private String versionTag;
    private String promptPreview;
    private List<RetrievalHit> hits;
}
