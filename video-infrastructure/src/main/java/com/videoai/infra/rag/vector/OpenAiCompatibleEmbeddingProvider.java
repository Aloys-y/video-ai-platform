package com.videoai.infra.rag.vector;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import com.videoai.infra.cost.AiCallRecorder.Outcome;
import com.videoai.infra.http.OneShotJsonBody;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Component
@ConditionalOnProperty(name="videoai.rag.embedding.provider",havingValue="openai-compatible")
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private final OpenAiEmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final AiCallRecorder recorder;
    private final OkHttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiCompatibleEmbeddingProvider(OpenAiEmbeddingProperties properties,ObjectMapper objectMapper,AiCallRecorder recorder) {
        this(properties,objectMapper,recorder,new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds())).build());
    }
    OpenAiCompatibleEmbeddingProvider(OpenAiEmbeddingProperties properties,ObjectMapper objectMapper,AiCallRecorder recorder,OkHttpClient httpClient) {
        this.properties=properties;this.objectMapper=objectMapper;this.recorder=Objects.requireNonNull(recorder);this.httpClient=httpClient;
    }
    @Override public List<Float> embed(String text) { return embedDocument(text); }
    @Override public List<Float> embedDocument(String text) { return requestEmbedding(null,text); }
    @Override public List<Float> embedQuery(String text) { return requestEmbedding(null,text); }
    @Override public List<Float> embedQuery(AiCallContext context,String text) {
        Objects.requireNonNull(context);
        if(context.stage()!=AiCallContext.Stage.RAG_EMBEDDING) throw new IllegalArgumentException("Embedding调用阶段不匹配");
        return requestEmbedding(context,text);
    }
    private List<Float> requestEmbedding(AiCallContext context,String text) {
        try {
            byte[] bytes=objectMapper.writeValueAsBytes(Map.of("model",properties.getModel(),"input",text));
            String base=properties.getBaseUrl();
            if(base==null || base.isBlank()) throw new IllegalArgumentException("Embedding接口地址为空");
            base=base.replaceAll("/+$", "");
            var request=new Request.Builder().url(base + "/embeddings").post(new OneShotJsonBody(bytes));
            if(properties.getApiKey()!=null && !properties.getApiKey().isBlank()) request.header("Authorization","Bearer "+properties.getApiKey());
            Duration timeout=ExecutionBudget.limit(Duration.ofSeconds(properties.getTimeoutSeconds()));
            var call=httpClient.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                    .readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout).build().newCall(request.build());
            String callId=context==null ? null : UUID.randomUUID().toString();
            if(callId!=null) recorder.begin(callId,context,properties.getModel());
            Outcome outcome=Outcome.UNKNOWN; AiUsage usage=AiUsage.unknown();
            String requestId=null;String errorCode="TRANSPORT_OR_RECEIPT_UNKNOWN";JsonNode root=null;
            try {
                try {ExecutionBudget.check();} catch(IOException cancelled) {outcome=Outcome.NOT_SENT;errorCode="CANCELLED_BEFORE_SEND";throw cancelled;}
                try(var response=call.execute()) {
                    if(response.body()==null) throw new IOException("Embedding响应为空");
                    byte[] body=response.body().byteStream().readNBytes(1024*1024+1);
                    if(body.length>1024*1024) throw new IOException("Embedding响应过大");
                    outcome=response.isSuccessful()?Outcome.SUCCEEDED:Outcome.FAILED;
                    errorCode="HTTP_"+response.code();
                    try {
                        root=objectMapper.readTree(body);
                        if(root!=null) {usage=AiCallRecorder.inputUsage(root.get("usage"));requestId=root.path("request_id").asText(root.path("id").asText(null));}
                    } catch(com.fasterxml.jackson.core.JsonProcessingException malformed) { /* 缺失用量保持未知。 */ }
                    if(!response.isSuccessful()) throw new IOException("Embedding HTTP请求失败");
                    errorCode=null;
                }
            } finally {if(callId!=null) recorder.finish(callId,outcome,usage,requestId,errorCode);}
            if(root==null) throw new IOException("Embedding响应无效");
            ExternalUsageReceipt.report(root.path("usage").toString(),requestId);
            var vectorNode=root.path("data").path(0).path("embedding");
            if(!vectorNode.isArray() || vectorNode.isEmpty()) throw new IllegalStateException("Embedding response missing vector data");
            List<Float> vector=new ArrayList<>(vectorNode.size());
            for(JsonNode node:vectorNode) {
                if(!node.isNumber() || !Float.isFinite(node.floatValue())) throw new IllegalStateException("Embedding response contains invalid vector");
                vector.add(node.floatValue());
            }
            return vector;
        } catch(IOException e) {throw new IllegalStateException("Embedding请求失败或响应无效，费用以账本回执为准");}
    }
}
