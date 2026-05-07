package com.videoai.worker.service.provider;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.videoai.worker.config.DashScopeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 阿里云DashScope Provider（Qwen-VL系列）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeVideoProvider implements AiVideoProvider {

    private final DashScopeConfig config;

    @Override
    public String call(String videoUrl, String prompt) throws AiProviderException {
        log.info("DashScope API request - model: {}, videoUrl: {}, timeout: {}s",
                config.getModel(), videoUrl, config.getTimeout());

        // 设置SDK超时，给大文件下载留足时间
        if (Constants.connectionConfigurations == null) {
            Constants.connectionConfigurations = com.alibaba.dashscope.protocol.ConnectionConfigurations.builder().build();
        }
        Constants.connectionConfigurations.setReadTimeout(
                java.time.Duration.ofSeconds(config.getTimeout()));
        Constants.connectionConfigurations.setConnectTimeout(
                java.time.Duration.ofSeconds(config.getConnectTimeout()));

        Map<String, Object> videoParams = new HashMap<>();
        videoParams.put("video", videoUrl);
        videoParams.put("fps", 2);

        Map<String, Object> textParams = new HashMap<>();
        textParams.put("text", prompt);

        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(Arrays.asList(videoParams, textParams))
                .build();

        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(config.getApiKey())
                .model(config.getModel())
                .messages(Collections.singletonList(userMessage))
                .build();

        MultiModalConversationResult result;
        try {
            MultiModalConversation conv = new MultiModalConversation();
            result = conv.call(param);
        } catch (ApiException e) {
            Status status = e.getStatus();
            String errorCode = status != null ? status.getCode() : null;
            int httpStatus = status != null ? status.getStatusCode() : 0;
            log.error("DashScope API error - httpStatus: {}, errorCode: {}, message: {}",
                    httpStatus, errorCode, e.getMessage(), e);
            boolean retryable = httpStatus == 429 ||
                    (errorCode != null && (errorCode.contains("Throttling") || errorCode.contains("RateLimit"))) ||
                    isTransientError(e.getMessage());
            throw new AiProviderException(
                    "DashScope API error: code=" + errorCode + ", " + e.getMessage(), e, retryable);
        } catch (NoApiKeyException e) {
            throw new AiProviderException("DashScope API Key未配置: " + e.getMessage(), false);
        } catch (Exception e) {
            log.error("DashScope SDK exception", e);
            throw new AiProviderException("SDK exception: " + e.getClass().getSimpleName() + " - " + e.getMessage(), false);
        }

        try {
            List<Map<String, Object>> contentList = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (contentList != null && !contentList.isEmpty()) {
                Object textObj = contentList.get(0).get("text");
                String text = textObj != null ? textObj.toString() : "";
                log.info("DashScope API response length: {}", text.length());
                return text;
            }
            throw new AiProviderException("DashScope API returned empty content", false);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse DashScope response", e);
            throw new AiProviderException("Failed to parse response: " + e.getMessage(), false);
        }
    }

    @Override
    public int getPresignedUrlExpireHours() {
        return config.getPresignedUrlExpireHours();
    }

    @Override
    public String getName() {
        return "DashScope(" + config.getModel() + ")";
    }

    /**
     * 判断是否为瞬态错误（网络超时等可重试错误）
     */
    private boolean isTransientError(String message) {
        if (message == null) return false;
        return message.contains("timed out") || message.contains("timeout")
                || message.contains("timed out") || message.contains("connection reset");
    }
}
