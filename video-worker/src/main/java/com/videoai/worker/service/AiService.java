package com.videoai.worker.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.videoai.worker.config.GlmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * AI视频分析服务
 *
 * 封装GLM多模态模型调用，支持video_url输入的视频理解
 * 包含429（平台过载）自动重试机制
 *
 * 注意：智谱 SDK 遇到 HTTP 429 时直接抛异常（ZAiHttpException），
 * 不会返回 response 对象，因此重试逻辑必须在 catch 块中处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ZhipuAiClient zhipuAiClient;
    private final GlmConfig glmConfig;

    /** 429重试最大次数 */
    private static final int MAX_429_RETRIES = 3;
    /** 429重试间隔（毫秒）：10s, 30s, 60s */
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

    /**
     * 分析视频内容（含429自动重试）
     *
     * SDK 在 HTTP 429 时抛异常而非返回 response，所以重试逻辑放在 catch 中
     *
     * @param videoUrl 视频公网URL
     * @return AI返回的分析结果（JSON字符串）
     */
    public String analyzeVideo(String videoUrl) {
        ChatCompletionCreateParams request = buildRequest(videoUrl);

        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_429_RETRIES; attempt++) {
            try {
                log.info("Calling GLM API, model: {}, attempt: {}/{}",
                        glmConfig.getModel(), attempt + 1, MAX_429_RETRIES + 1);

                ChatCompletionResponse response = zhipuAiClient.chat().createChatCompletion(request);

                if (response.isSuccess() && response.getData() != null
                        && response.getData().getChoices() != null
                        && !response.getData().getChoices().isEmpty()) {

                    Object content = response.getData().getChoices().get(0).getMessage().getContent();
                    String result = content != null ? content.toString() : "";

                    log.info("GLM API response received, length: {}", result.length());
                    return result;
                }

                // response 返回但非成功（非 429，比如参数错误）
                String errorMsg = response.getMsg() != null ? response.getMsg() : "Unknown error";
                log.error("GLM API call failed: {}", errorMsg);
                throw new RuntimeException("GLM API call failed: " + errorMsg);

            } catch (Exception e) {
                lastException = e;

                // 拼接整个异常链的消息（SDK 包装多层，429 信息在 cause 中）
                StringBuilder sb = new StringBuilder();
                Throwable t = e;
                while (t != null) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(t.getClass().getSimpleName());
                    if (t.getMessage() != null) {
                        sb.append(": ").append(t.getMessage());
                    }
                    t = t.getCause();
                }
                String msg = sb.toString();
                log.warn("[AiService] Exception chain: {}", msg);

                // 判断是否为 429（平台过载）
                // SDK 内部吞掉 ZAiHttpException，重新抛 RuntimeException("Call Failed")，无 cause
                // 因此匹配异常链关键字 + "Call Failed"（SDK 对 429/5xx 的统一包装）
                boolean isRetryable = msg.contains("429") || msg.contains("overload")
                        || msg.contains("overloaded") || msg.contains("Call Failed");

                if (isRetryable && attempt < MAX_429_RETRIES) {
                    int delay = RETRY_DELAYS_MS[attempt];
                    log.warn("GLM API retryable error, retrying in {}ms, attempt: {}/{}",
                            delay, attempt + 1, MAX_429_RETRIES);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("GLM API call interrupted during 429 retry", ie);
                    }
                    continue;
                }

                // 非 429 或重试耗尽
                throw new RuntimeException("GLM API call failed: " + msg, e);
            }
        }

        throw new RuntimeException("GLM API call failed after " + MAX_429_RETRIES + " retries",
                lastException);
    }

    private ChatCompletionCreateParams buildRequest(String videoUrl) {
        return ChatCompletionCreateParams.builder()
                .model(glmConfig.getModel())
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
                                                .text("请分析这个视频的内容，按照系统提示的JSON格式返回结果。")
                                                .build()
                                ))
                                .build()
                ))
                .maxTokens(glmConfig.getMaxTokens())
                .build();
    }
}
