package com.videoai.worker.service.provider;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.videoai.worker.config.ZhipuConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 智谱GLM Provider（GLM-4.6V / GLM-5V系列）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "zhipu")
public class ZhipuVideoProvider implements AiVideoProvider {

    private final ZhipuAiClient zhipuAiClient;
    private final ZhipuConfig config;

    @Override
    public String call(String videoUrl, String prompt) throws AiProviderException {
        log.info("Zhipu API request - model: {}, videoUrl: {}", config.getModel(), videoUrl);

        // Zhipu SDK 支持独立的 system message
        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(config.getModel())
                .messages(Arrays.asList(
                        ChatMessage.builder()
                                .role(ChatMessageRole.SYSTEM.value())
                                .content("你是一个专业的视频内容分析助手。")
                                .build(),
                        ChatMessage.builder()
                                .role(ChatMessageRole.USER.value())
                                .content(Arrays.asList(
                                        MessageContent.builder()
                                                .type("video_url")
                                                .videoUrl(VideoUrl.builder()
                                                        .url(videoUrl)
                                                        .build())
                                                .build(),
                                        MessageContent.builder()
                                                .type("text")
                                                .text(prompt)
                                                .build()
                                ))
                                .build()
                ))
                .maxTokens(config.getMaxTokens())
                .build();

        ChatCompletionResponse response;
        try {
            response = zhipuAiClient.chat().createChatCompletion(request);
        } catch (Exception e) {
            log.error("Zhipu SDK exception - model: {}, videoUrl: {}", config.getModel(), videoUrl, e);
            throw new AiProviderException("Zhipu SDK exception: " + e.getClass().getSimpleName() + " - " + e.getMessage(), false);
        }

        log.info("Zhipu API response - success: {}, code: {}", response.isSuccess(), response.getCode());

        if (response.isSuccess()) {
            ChatMessage message = (ChatMessage) response.getData().getChoices().get(0).getMessage();
            Object content = message.getContent();
            String result = content != null ? content.toString() : "";
            log.info("Zhipu API response length: {}", result.length());
            return result;
        }

        // 解析错误
        String errorMsg = response.getMsg();
        int httpCode = response.getCode();
        ChatError error = (ChatError) response.getError();
        String bizCode = null;
        String bizMsg = null;
        if (error != null) {
            bizCode = error.getCode() != null ? String.valueOf(error.getCode()) : null;
            bizMsg = error.getMessage();
        }

        log.error("Zhipu API error - httpCode: {}, bizCode: {}, bizMsg: {}", httpCode, bizCode, bizMsg);

        // 账户异常不可重试
        if (bizCode != null && (bizCode.equals("1113") || bizCode.equals("1112")
                || bizCode.equals("1121") || bizCode.equals("1110"))) {
            throw new AiProviderException("账户异常(不可重试): bizCode=" + bizCode + ", " + bizMsg, false);
        }
        // 限流可重试
        if (httpCode == 429 || (bizCode != null && (bizCode.equals("1302") || bizCode.equals("1303")
                || bizCode.equals("1304") || bizCode.equals("1305") || bizCode.equals("1312")))) {
            throw new AiProviderException("限流(可重试): bizCode=" + bizCode + ", " + bizMsg, true);
        }

        throw new AiProviderException("Zhipu API error: code=" + httpCode + ", msg=" + errorMsg, false);
    }

    @Override
    public int getPresignedUrlExpireHours() {
        return config.getPresignedUrlExpireHours();
    }

    @Override
    public String getName() {
        return "Zhipu(" + config.getModel() + ")";
    }
}
