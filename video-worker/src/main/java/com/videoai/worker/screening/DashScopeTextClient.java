package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import com.videoai.infra.cost.AiCallRecorder.Outcome;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;
import com.videoai.infra.http.OneShotJsonBody;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class DashScopeTextClient implements AiTextClient {
    private final TextAnalysisProperties properties;
    private final DashScopeConfig fallback;
    private final ObjectMapper json;
    private final OkHttpClient http;
    private final AiCallRecorder recorder;

    @org.springframework.beans.factory.annotation.Autowired
    public DashScopeTextClient(TextAnalysisProperties properties, DashScopeConfig fallback, ObjectMapper json, AiCallRecorder recorder) {
        this(properties, fallback, json, recorder, new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).writeTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .callTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build());
    }
    DashScopeTextClient(TextAnalysisProperties properties, DashScopeConfig fallback, ObjectMapper json, AiCallRecorder recorder, OkHttpClient http) {
        this.properties = properties; this.fallback = fallback; this.json = json; this.http = http; this.recorder = Objects.requireNonNull(recorder);
    }
    @Override public String complete(AiCallContext context, String systemPrompt, String userText) throws IOException {
        Objects.requireNonNull(context);
        if (context.stage() != AiCallContext.Stage.TEXT_SCREEN) throw new IllegalArgumentException("文本粗筛调用阶段不匹配");
        properties.validate();
        if (systemPrompt.getBytes(StandardCharsets.UTF_8).length + userText.getBytes(StandardCharsets.UTF_8).length + 1024 > properties.getMaxRequestBytes())
            throw new IOException("文本请求超出单批输入预算");
        URI base;
        try { base = URI.create(properties.getBaseUrl()); } catch (RuntimeException e) { throw new IOException("文本接口地址无效"); }
        String host = base.getHost();
        if (!"https".equals(base.getScheme()) || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || host == null || !(host.equals("dashscope.aliyuncs.com") || host.equals("dashscope-intl.aliyuncs.com") || host.endsWith(".maas.aliyuncs.com")))
            throw new IOException("文本凭据仅允许发送到百炼HTTPS接口");
        String key = properties.getApiKey();
        if (key == null || key.isBlank()) key = fallback.getApiKey();
        if (key == null || key.isBlank()) throw new IOException("未配置文本模型凭据");
        byte[] body = json.writeValueAsBytes(Map.of("model", properties.getModel(), "temperature", 0,
                "max_tokens", properties.getMaxOutputTokens(), "enable_thinking", false,
                "messages", List.of(Map.of("role", "system", "content", systemPrompt), Map.of("role", "user", "content", userText))));
        Request request = new Request.Builder().url(properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions")
                .header("Authorization", "Bearer " + key).post(new OneShotJsonBody(body)).build();
        Call call = http.newCall(request);
        call.timeout().timeout(com.videoai.common.analysis.ExecutionBudget.limit(Duration.ofSeconds(properties.getTimeoutSeconds())).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        String callId = UUID.randomUUID().toString();
        recorder.begin(callId, context, properties.getModel());
        Outcome outcome = Outcome.UNKNOWN;
        AiUsage usage = AiUsage.unknown();
        String requestId = null;
        String errorCode = "TRANSPORT_OR_RECEIPT_UNKNOWN";
        try {
            // 登记后、发送前再次检查取消；只有此处能明确证明未发送。
            try {
                ExecutionBudget.check();
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("文本请求已中断");
            } catch (IOException cancelled) {
                outcome = Outcome.NOT_SENT; errorCode = "CANCELLED_BEFORE_SEND"; throw cancelled;
            }
            try (Response response = call.execute()) {
                errorCode = "HTTP_" + response.code();
                if (response.body() == null) throw new IOException("文本API没有响应体");
                byte[] bytes = response.body().byteStream().readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("文本响应超出1MiB限制");
                String raw = new String(bytes, StandardCharsets.UTF_8);
                // HTTP 调用成功与交战区间解析成功是两个概念。错误响应也可能携带用量。
                outcome = response.isSuccessful() ? Outcome.SUCCEEDED : Outcome.FAILED;
                try {
                    var root = json.readTree(raw);
                    if (root != null && root.isObject()) {
                        usage = AiCallRecorder.tokenUsage(root.get("usage"));
                        requestId = root.path("request_id").asText(null);
                        if (requestId == null) requestId = root.path("id").asText(null);
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalidReceipt) {
                    // 原始响应仍交由业务层保存；不能把未知用量当成零费用。
                }
                if (!response.isSuccessful()) throw new IOException("文本API请求失败");
                errorCode = null;
                return raw;
            }
        } catch (IOException e) {
            throw new IOException("文本模型调用失败或超时；已保留提交记录，不自动重发");
        } finally {
            // 收尾失败由 Recorder 有限重试；返回 false 不影响已获得的模型结果。
            recorder.finish(callId, outcome, usage, requestId, errorCode);
        }
    }
}
