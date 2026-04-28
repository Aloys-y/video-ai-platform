package com.videoai.worker.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.videoai.worker.config.ZhipuConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * AI视频分析服务
 *
 * 使用智谱官方SDK + GLM-4.6V-Flash（免费）
 * 原生支持 video_url 视频理解
 * 支持429（限流）自动重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ZhipuAiClient zhipuAiClient;
    private final ZhipuConfig config;

    /** 429重试最大次数 */
    private static final int MAX_RETRIES = 3;
    /** 重试间隔（毫秒）：10s, 30s, 60s */
    private static final int[] RETRY_DELAYS_MS = {10_000, 30_000, 60_000};

    private static final String SYSTEM_PROMPT = """
            你是一个专业的视频内容分析助手。请对视频进行全面分析，返回以下JSON格式：
            {
              "summary": "视频内容摘要（100字以内）",
              "scenes": [
                {
                  "timeRange": "00:00-00:10",
                  "description": "场景描述",
                  "type": "场景类型（如：对话、动作、风景、文字等）"
                }
              ],
              "keyframes": [
                {
                  "time": "00:05",
                  "description": "关键帧描述"
                }
              ],
              "tags": ["标签1", "标签2"],
              "sentiment": "整体情感倾向（正面/中性/负面）",
              "textDetected": "视频中检测到的文字内容（如有）"
            }
            请确保返回有效的JSON格式。
            """;

    private static final String DEFAULT_USER_PROMPT = "请分析这个视频的内容，按照系统提示的JSON格式返回结果。";

    /**
     * 分析视频内容（含429自动重试）
     *
     * @param videoUrl 视频公网URL
     * @param prompt   用户自定义提示词（可为null，使用默认）
     * @return AI返回的分析结果（JSON字符串）
     */
    public String analyzeVideo(String videoUrl, String prompt) {
        String userPrompt = (prompt != null && !prompt.isBlank()) ? prompt : DEFAULT_USER_PROMPT;
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Calling Zhipu API, model: {}, attempt: {}/{}",
                        config.getModel(), attempt + 1, MAX_RETRIES + 1);

                String result = doCall(videoUrl, userPrompt);
                log.info("Zhipu API response received, length: {}", result.length());
                return result;

            } catch (RateLimitException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    int delay = RETRY_DELAYS_MS[attempt];
                    log.warn("Zhipu API rate limited, retrying in {}ms, attempt: {}/{}",
                            delay, attempt + 1, MAX_RETRIES);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("API call interrupted during retry", ie);
                    }
                    continue;
                }
                throw new RuntimeException("Zhipu API call failed after " + MAX_RETRIES + " retries", e);

            } catch (ApiCallException e) {
                throw new RuntimeException("Zhipu API call failed: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException("Zhipu API call failed after " + MAX_RETRIES + " retries",
                lastException);
    }

    /**
     * 执行一次API调用
     */
    private String doCall(String videoUrl, String userPrompt) throws RateLimitException, ApiCallException {
        log.info("Zhipu API request - model: {}, videoUrl: {}, maxTokens: {}, promptLength: {}",
                config.getModel(), videoUrl, config.getMaxTokens(),
                userPrompt != null ? userPrompt.length() : 0);

        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(config.getModel())
                .messages(Arrays.asList(
                        ChatMessage.builder()
                                .role(ChatMessageRole.SYSTEM.value())
                                .content(SYSTEM_PROMPT)
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
                                                .text(userPrompt)
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
            log.error("Zhipu API call threw exception - model: {}, videoUrl: {}", config.getModel(), videoUrl, e);
            throw new ApiCallException("SDK exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        // 完整记录响应结构
        log.info("Zhipu API response - success: {}, code: {}, msg: {}, data: {}",
                response.isSuccess(), response.getCode(), response.getMsg(), response.getData());

        if (response.isSuccess()) {
            ChatMessage message = (ChatMessage) response.getData().getChoices().get(0).getMessage();
            Object content = message.getContent();
            String result = content != null ? content.toString() : "";
            log.info("Zhipu API response content length: {}", result.length());
            return result;
        }

        // 判断是否是限流
        String errorMsg = response.getMsg();
        int code = response.getCode();
        log.error("Zhipu API error - code: {}, msg: {}, model: {}, videoUrl: {}",
                code, errorMsg, config.getModel(), videoUrl);

        if (code == 429 || (errorMsg != null && errorMsg.contains("rate"))) {
            throw new RateLimitException("HTTP 429: " + errorMsg);
        }

        throw new ApiCallException("code=" + code + ", msg=" + errorMsg);
    }

    /** 限流异常（可重试） */
    private static class RateLimitException extends Exception {
        RateLimitException(String message) { super(message); }
    }

    /** API调用异常（不可重试） */
    private static class ApiCallException extends Exception {
        ApiCallException(String message) { super(message); }
    }
}
