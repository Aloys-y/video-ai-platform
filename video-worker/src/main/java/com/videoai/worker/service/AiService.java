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
     * @param videoUrl 视频公网URL
     * @return AI返回的分析结果（JSON字符串）
     */
    public String analyzeVideo(String videoUrl) {
        ChatCompletionCreateParams request = buildRequest(videoUrl);

        for (int attempt = 0; attempt <= MAX_429_RETRIES; attempt++) {
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

            // 检查是否为429（平台过载）
            String errorMsg = response.getMsg() != null ? response.getMsg() : "";
            boolean is429 = errorMsg.contains("429") || errorMsg.contains("rate")
                    || errorMsg.contains("Rate") || errorMsg.contains("overload")
                    || errorMsg.contains("过载") || errorMsg.contains("限流");

            if (is429 && attempt < MAX_429_RETRIES) {
                int delay = RETRY_DELAYS_MS[attempt];
                log.warn("GLM API 429 (platform overload), retrying in {}ms, attempt: {}/{}",
                        delay, attempt + 1, MAX_429_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("GLM API call interrupted during 429 retry", e);
                }
                continue;
            }

            // 非429错误或重试耗尽
            log.error("GLM API call failed: {}", errorMsg);
            throw new RuntimeException("GLM API call failed: " + errorMsg);
        }

        throw new RuntimeException("GLM API call failed after " + MAX_429_RETRIES + " retries (429)");
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
