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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import okhttp3.*;

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
                ragProperties, embeddingProperties, objectMapper, org.mockito.Mockito.mock(com.videoai.infra.cost.AiCallRecorder.class));

        List<RerankResult> results = service.rerank("穿刺尖刺有什么机制？", List.of(
                hit("catalyst_1", "基础机制"),
                hit("catalyst_2", "门边布置技巧")));

        assertEquals(List.of(new RerankResult(1, 0.91), new RerankResult(0, 0.82)), results);
        assertEquals("Bearer shared-key", capturedAuthorization.get());
        assertEquals(2, capturedBody.get().path("documents").size());
        assertEquals("qwen3-rerank", capturedBody.get().path("model").asText());
    }

    private final AiCallRecorder recorder=mock(AiCallRecorder.class);
    private final AiCallContext context=new AiCallContext("video",3,AiCallContext.Stage.RAG_RERANK,0);
    private final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
    private RerankService client(int status,String body) {
        var properties=new RagProperties();var embedding=new OpenAiEmbeddingProperties();embedding.setApiKey("test-key");
        var http=new OkHttpClient.Builder().addInterceptor(chain->{
            calls.incrementAndGet();assertTrue(chain.request().body().isOneShot());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("mock")
                    .body(ResponseBody.create(MediaType.parse("application/json"),body)).build();
        }).build();
        return new RerankService(properties,embedding,new ObjectMapper(),recorder,http);
    }
    @Test void emptyCandidatesDoNotCreateCall() {
        assertTrue(client(200,"{}").rerank(context,"q",List.of()).isEmpty());
        verifyNoInteractions(recorder);assertEquals(0,calls.get());
    }
    @Test void invalidRankingStillRetainsUsage() {
        assertThrows(IllegalStateException.class,()->client(200,"{\"usage\":{\"total_tokens\":200},\"results\":[]}")
                .rerank(context,"q",List.of(hit("a","text"))));
        verify(recorder).begin(anyString(),eq(context),eq("qwen3-rerank"));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.SUCCEEDED),argThat(u->Long.valueOf(200).equals(u.inputTokens())),isNull(),isNull());
    }
    @Test void beginFailurePreventsCall() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(recorder).begin(anyString(),any(),anyString());
        assertThrows(org.springframework.dao.DataAccessException.class,()->client(200,"{}").rerank(context,"q",List.of(hit("a","text"))));
        assertEquals(0,calls.get());
    }
    @Test void errorReceiptRetainsUsageWithoutClientRetry() {
        assertThrows(IllegalStateException.class,()->client(503,"{\"usage\":{\"input_tokens\":17}}")
                .rerank(context,"q",List.of(hit("a","text"))));
        verify(recorder).finish(anyString(),eq(AiCallRecorder.Outcome.FAILED),argThat(u->Long.valueOf(17).equals(u.inputTokens())),isNull(),eq("HTTP_503"));
        assertEquals(1,calls.get());
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
