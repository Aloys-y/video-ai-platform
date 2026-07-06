package com.videoai.infra.rag.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "videoai.rag.embedding.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    private static final String EMBEDDING_PATH = "/services/embeddings/text-embedding/text-embedding";

    private final OpenAiEmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DashScopeEmbeddingProvider(OpenAiEmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public List<Float> embed(String text) {
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getModel(),
                    "input", Map.of("texts", List.of(text)),
                    "parameters", Map.of(
                            "text_type", properties.getDashscope().getTextType(),
                            "dimension", properties.getDimension()
                    )
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + EMBEDDING_PATH))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("DashScope embedding request failed, status="
                        + response.statusCode() + ", body=" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode vectorNode = root.path("output").path("embeddings").path(0).path("embedding");
            if (!vectorNode.isArray() || vectorNode.isEmpty()) {
                throw new IllegalStateException("DashScope embedding response missing vector data");
            }

            List<Float> vector = new ArrayList<>(vectorNode.size());
            for (JsonNode node : vectorNode) {
                vector.add(node.floatValue());
            }
            return vector;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("DashScope embedding request failed", e);
            throw new IllegalStateException("DashScope embedding request failed: " + e.getMessage(), e);
        }
    }

    private String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://dashscope.aliyuncs.com/api/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
