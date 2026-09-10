package com.videoai.rag.model;

import com.videoai.common.domain.KnowledgeChunk;

/** BM25 词法召回结果。原始分数只用于同一查询内排序，跨查询不可直接比较。 */
public record LexicalSearchResult(KnowledgeChunk chunk, double score) {
}
