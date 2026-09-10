package com.videoai.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.rag.RetrievalHit;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.model.RerankResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文预训练 Reranker 客户端。一次请求批量重排全部向量候选，不在客户端按章节去重。
 */
@Service
public class RerankService {

    private final RagProperties ragProperties;
    private final OpenAiEmbeddingProperties embeddingProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RerankService(RagProperties ragProperties,
                         OpenAiEmbeddingProperties embeddingProperties,
                         ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.embeddingProperties = embeddingProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(
                        Math.max(1, ragProperties.getRerankConnectTimeoutMillis())))
                .build();
    }

    public List<RerankResult> rerank(String query, List<RetrievalHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        String apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Reranker API key is not configured");
        }

        List<String> documents = candidates.stream().map(this::toDocument).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", ragProperties.getRerankModel());
        payload.put("query", query);
        payload.put("documents", documents);
        payload.put("top_n", documents.size());
        payload.put("instruct", ragProperties.getRerankInstruction());

        try {
            String requestJson = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ragProperties.getRerankBaseUrl()))
                    .timeout(Duration.ofMillis(Math.max(1, ragProperties.getRerankTimeoutMillis())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Reranker request failed, status="
                        + response.statusCode() + ", body=" + abbreviate(response.body()));
            }
            var receipt = objectMapper.readTree(response.body());
            com.videoai.common.analysis.ExternalUsageReceipt.report(receipt.path("usage").toString(), receipt.path("request_id").asText(receipt.path("id").asText()));
            return parseResults(response.body(), candidates.size());
        } catch (IOException e) {
            throw new IllegalStateException("Reranker request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reranker request interrupted", e);
        }
    }

    private List<RerankResult> parseResults(String responseBody, int candidateCount)
            throws IOException {
        JsonNode results = objectMapper.readTree(responseBody).path("results");
        if (!results.isArray() || results.size() != candidateCount) {
            throw new IllegalStateException("Reranker response has unexpected result count");
        }

        boolean[] seen = new boolean[candidateCount];
        List<RerankResult> reranked = new ArrayList<>(candidateCount);
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            JsonNode scoreNode = result.path("relevance_score");
            if (index < 0 || index >= candidateCount || seen[index] || !scoreNode.isNumber()) {
                throw new IllegalStateException("Reranker response contains invalid result");
            }
            seen[index] = true;
            reranked.add(new RerankResult(index, scoreNode.doubleValue()));
        }
        return List.copyOf(reranked);
    }

    private String toDocument(RetrievalHit candidate) {
        return "英雄：" + value(candidate.getTitle()) + "\n"
                + "章节：" + value(candidate.getHeadingPath()) + "\n"
                + "正文：" + value(candidate.getContentText());
    }

    private String resolveApiKey() {
        String configured = value(ragProperties.getRerankApiKey()).trim();
        return configured.isEmpty() ? value(embeddingProperties.getApiKey()).trim() : configured;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value) {
        String safe = value(value);
        return safe.length() <= 500 ? safe : safe.substring(0, 500) + "...";
    }
}
