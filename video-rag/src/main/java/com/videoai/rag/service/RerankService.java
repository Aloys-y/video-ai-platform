package com.videoai.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.rag.RetrievalHit;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.model.RerankResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import okhttp3.*;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import com.videoai.infra.cost.AiCallRecorder.Outcome;
import com.videoai.infra.http.OneShotJsonBody;
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
    private final OkHttpClient httpClient;
    private final AiCallRecorder recorder;

    @org.springframework.beans.factory.annotation.Autowired
    public RerankService(RagProperties ragProperties,OpenAiEmbeddingProperties embeddingProperties,
                         ObjectMapper objectMapper,AiCallRecorder recorder) {
        this(ragProperties,embeddingProperties,objectMapper,recorder,new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(Math.max(1,ragProperties.getRerankConnectTimeoutMillis()))).build());
    }
    RerankService(RagProperties ragProperties,OpenAiEmbeddingProperties embeddingProperties,
                  ObjectMapper objectMapper,AiCallRecorder recorder,OkHttpClient httpClient) {
        this.ragProperties=ragProperties;this.embeddingProperties=embeddingProperties;
        this.objectMapper=objectMapper;this.recorder=java.util.Objects.requireNonNull(recorder);this.httpClient=httpClient;
    }
    /** 离线评测入口，不归入某条视频任务费用。 */
    public List<RerankResult> rerank(String query,List<RetrievalHit> candidates) {
        return requestRerank(null,query,candidates);
    }
    public List<RerankResult> rerank(AiCallContext context,String query,List<RetrievalHit> candidates) {
        java.util.Objects.requireNonNull(context);
        if(context.stage()!=AiCallContext.Stage.RAG_RERANK) throw new IllegalArgumentException("Rerank调用阶段不匹配");
        return requestRerank(context,query,candidates);
    }
    private List<RerankResult> requestRerank(AiCallContext context,String query,List<RetrievalHit> candidates) {
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
            var request=new Request.Builder().url(ragProperties.getRerankBaseUrl())
                    .header("Authorization","Bearer "+apiKey)
                    .post(new OneShotJsonBody(objectMapper.writeValueAsBytes(payload))).build();
            Duration timeout=ExecutionBudget.limit(Duration.ofMillis(Math.max(1,ragProperties.getRerankTimeoutMillis())));
            var call=httpClient.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                    .readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout).build().newCall(request);
            String callId=context==null?null:java.util.UUID.randomUUID().toString();
            if(callId!=null) recorder.begin(callId,context,ragProperties.getRerankModel());
            Outcome outcome=Outcome.UNKNOWN;AiUsage usage=AiUsage.unknown();String requestId=null;
            String errorCode="TRANSPORT_OR_RECEIPT_UNKNOWN";String raw=null;JsonNode receipt=null;
            try {
                try {ExecutionBudget.check();}catch(IOException cancelled){outcome=Outcome.NOT_SENT;errorCode="CANCELLED_BEFORE_SEND";throw cancelled;}
                try(var response=call.execute()) {
                    if(response.body()==null) throw new IOException("Rerank响应为空");
                    byte[] bytes=response.body().byteStream().readNBytes(1024*1024+1);
                    if(bytes.length>1024*1024) throw new IOException("Rerank响应过大");
                    raw=new String(bytes,StandardCharsets.UTF_8);
                    outcome=response.isSuccessful()?Outcome.SUCCEEDED:Outcome.FAILED;errorCode="HTTP_"+response.code();
                    try {
                        receipt=objectMapper.readTree(raw);
                        if(receipt!=null){usage=AiCallRecorder.inputUsage(receipt.get("usage"));requestId=receipt.path("request_id").asText(receipt.path("id").asText(null));}
                    }catch(com.fasterxml.jackson.core.JsonProcessingException malformed){ /* 未知用量不当成零。 */ }
                    if(!response.isSuccessful()) throw new IOException("Rerank HTTP请求失败");
                    errorCode=null;
                }
            }finally{if(callId!=null)recorder.finish(callId,outcome,usage,requestId,errorCode);}
            if(receipt==null) throw new IOException("Rerank响应无效");
            ExternalUsageReceipt.report(receipt.path("usage").toString(),requestId);
            return parseResults(raw,candidates.size());
        }catch(IOException e){throw new IllegalStateException("Rerank请求失败或响应无效，费用以账本回执为准");}
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

}
