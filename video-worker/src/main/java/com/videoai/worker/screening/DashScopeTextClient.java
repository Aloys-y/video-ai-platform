package com.videoai.worker.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;
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

    @org.springframework.beans.factory.annotation.Autowired
    public DashScopeTextClient(TextAnalysisProperties properties, DashScopeConfig fallback, ObjectMapper json) {
        this(properties, fallback, json, new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).writeTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .callTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build());
    }
    DashScopeTextClient(TextAnalysisProperties properties, DashScopeConfig fallback, ObjectMapper json, OkHttpClient http) {
        this.properties = properties; this.fallback = fallback; this.json = json; this.http = http;
    }
    @Override public String complete(String systemPrompt, String userText) throws IOException {
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
                .header("Authorization", "Bearer " + key).post(RequestBody.create(MediaType.parse("application/json"), body)).build();
        Call call = http.newCall(request);
        call.timeout().timeout(com.videoai.common.analysis.ExecutionBudget.limit(Duration.ofSeconds(properties.getTimeoutSeconds())).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("文本API请求失败");
            byte[] bytes = response.body().byteStream().readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("文本响应超出1MiB限制");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IOException("文本模型调用失败或超时；已保留提交记录，不自动重发"); }
    }
}
