package com.videoai.infra.rag.vector;

import com.videoai.infra.rag.model.VectorRecord;
import com.videoai.infra.rag.model.VectorSearchResult;

import java.util.List;
import java.util.Map;

public interface VectorStoreClient {

    void ensureCollection();

    void upsert(List<VectorRecord> records);

    void deleteByIds(List<String> ids);

    List<VectorSearchResult> search(List<Float> vector, int topK, String filterExpression);

    /** 只读取元数据并按 card_code 统计向量数，用于索引覆盖审计。 */
    Map<String, Long> countByCard(String filterExpression);
}
