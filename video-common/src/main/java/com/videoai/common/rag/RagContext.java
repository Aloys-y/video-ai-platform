package com.videoai.common.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagContext {

    private String baseCode;
    private String versionTag;
    private String queryText;
    private List<RetrievalHit> hits;
    private String contextText;
    private String status;
    private int latencyMs;
}
