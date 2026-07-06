package com.videoai.infra.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class VectorSearchResult {

    private String id;

    private double score;

    private Map<String, Object> fields;
}
