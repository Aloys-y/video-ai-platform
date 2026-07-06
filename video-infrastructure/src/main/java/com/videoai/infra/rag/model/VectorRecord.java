package com.videoai.infra.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class VectorRecord {

    private String id;

    private List<Float> vector;     // 向量

    private Map<String, Object> fields; // 元数据字段
}
