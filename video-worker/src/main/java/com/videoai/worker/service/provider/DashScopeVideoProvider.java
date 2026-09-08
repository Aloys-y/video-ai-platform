package com.videoai.worker.service.provider;

import com.alibaba.dashscope.aigc.multimodalconversation.*;
import com.alibaba.dashscope.common.*;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.videoai.worker.config.DashScopeConfig;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** 每个请求独立参数、连接选项及 SDK 会话，不修改 Constants 静态配置。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeVideoProvider implements AiVideoProvider {
    private final DashScopeConfig config;

    @Override public String call(String videoUrl, String prompt) throws AiProviderException {
        return request(videoUrl, prompt, false).text();
    }

    @Override public DetailedResult callDetailed(String videoUrl, String prompt) throws AiProviderException {
        return request(videoUrl, prompt, true);
    }

    private DetailedResult request(String videoUrl, String prompt, boolean structured) throws AiProviderException {
        var builder = MultiModalConversationParam.builder().apiKey(config.getApiKey()).model(config.getModel())
                .messages(List.of(MultiModalMessage.builder().role(Role.USER.getValue())
                        .content(List.of(Map.of("video", videoUrl, "fps", 2), Map.of("text", prompt))).build()));
        if (structured) builder.maxTokens(config.getMaxTokens()).enableThinking(false).temperature(0f);
        ConnectionOptions options;
        try {
            var timeout = com.videoai.common.analysis.ExecutionBudget.limit(Duration.ofSeconds(config.getTimeout()));
            options = ConnectionOptions.builder().connectTimeout(com.videoai.common.analysis.ExecutionBudget.limit(Duration.ofSeconds(config.getConnectTimeout())))
                    .readTimeout(timeout).writeTimeout(timeout).build();
        } catch (java.io.IOException e) { throw new AiProviderException("任务总期限已耗尽", false); }
        options.setUseDefaultClient(false);
        MultiModalConversationResult result;
        try { result = invoke(builder.build(), options); }
        catch (ApiException e) {
            Status status = e.getStatus();
            int code = status == null ? 0 : status.getStatusCode();
            // 厂商异常可能含签名 URL，不保留原 message/cause 到业务日志。
            throw new AiProviderException("DashScope 请求失败，HTTP=" + code, code == 429 || code >= 500);
        } catch (Exception e) { throw new AiProviderException("DashScope 请求失败或超时", false); }
        try {
            var choice = result.getOutput().getChoices().get(0);
            StringBuilder text = new StringBuilder();
            for (var item : choice.getMessage().getContent()) if (item.get("text") != null) text.append(item.get("text"));
            return new DetailedResult(text.toString(), result.getUsage() == null ? null : new Gson().toJson(result.getUsage()),
                    result.getRequestId(), choice.getFinishReason());
        } catch (Exception e) { throw new AiProviderException("DashScope 响应结构无效", false); }
    }

    protected MultiModalConversationResult invoke(MultiModalConversationParam param, ConnectionOptions options) throws Exception {
        return new MultiModalConversation("http", "https://dashscope.aliyuncs.com/api/v1", options).call(param);
    }

    @Override public Map<String, Object> segmentSettings() {
        return new TreeMap<>(Map.of("provider", "dashscope", "supported", true, "model", config.getModel(),
                "maxTokens", config.getMaxTokens(), "fps", 2, "thinking", false, "temperature", 0,
                "timeoutSeconds", config.getTimeout(), "connectTimeoutSeconds", config.getConnectTimeout()));
    }
    @Override public int getPresignedUrlExpireHours() { return config.getPresignedUrlExpireHours(); }
    @Override public String getName() { return "DashScope(" + config.getModel() + ")"; }
}
