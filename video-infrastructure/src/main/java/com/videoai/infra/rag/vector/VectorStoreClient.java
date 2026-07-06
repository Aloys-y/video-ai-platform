package com.videoai.infra.rag.vector;

import com.videoai.infra.rag.model.VectorRecord;
import com.videoai.infra.rag.model.VectorSearchResult;

import java.util.List;

public interface VectorStoreClient {

    void ensureCollection();

    void upsert(List<VectorRecord> records);

    void deleteByIds(List<String> ids);

    List<VectorSearchResult> search(List<Float> vector, int topK, String filterExpression);
}
