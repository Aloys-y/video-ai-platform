package com.videoai.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.videoai.common.rag.RetrievalHit;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.model.RerankResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RerankServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldBatchAllChunksAndReuseEmbeddingApiKey() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuthorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reranks", exchange -> {
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = ("{\"results\":["
                    + "{\"index\":1,\"relevance_score\":0.91},"
                    + "{\"index\":0,\"relevance_score\":0.82}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        RagProperties ragProperties = new RagProperties();
        ragProperties.setRerankBaseUrl("http://127.0.0.1:"
                + server.getAddress().getPort() + "/reranks");
        OpenAiEmbeddingProperties embeddingProperties = new OpenAiEmbeddingProperties();
        embeddingProperties.setApiKey("shared-key");
        RerankService service = new RerankService(
                ragProperties, embeddingProperties, objectMapper);

        List<RerankResult> results = service.rerank("穿刺尖刺有什么机制？", List.of(
                hit("catalyst_1", "基础机制"),
                hit("catalyst_2", "门边布置技巧")));

        assertEquals(List.of(new RerankResult(1, 0.91), new RerankResult(0, 0.82)), results);
        assertEquals("Bearer shared-key", capturedAuthorization.get());
        assertEquals(2, capturedBody.get().path("documents").size());
        assertEquals("qwen3-rerank", capturedBody.get().path("model").asText());
    }

    private RetrievalHit hit(String vectorId, String content) {
        return RetrievalHit.builder()
                .vectorId(vectorId)
                .cardCode("catalyst")
                .title("催化姬")
                .headingPath("催化姬 > 技能 > 穿刺尖刺")
                .contentText(content)
                .score(0.60)
                .build();
    }
}
