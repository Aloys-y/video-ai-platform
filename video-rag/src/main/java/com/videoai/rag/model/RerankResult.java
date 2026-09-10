package com.videoai.rag.model;

/**
 * Reranker 对单个候选的输出。index 指向本次请求的原始候选下标。
 */
public record RerankResult(int index, double score) {
}
