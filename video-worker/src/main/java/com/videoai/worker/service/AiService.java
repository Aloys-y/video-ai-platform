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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ZhipuAiClient zhipuAiClient;
    private final GlmConfig glmConfig;

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
     * 分析视频内容
     *
     * @param videoUrl 视频公网URL
     * @return AI返回的分析结果（JSON字符串）
     */
    public String analyzeVideo(String videoUrl) {
        log.info("Calling GLM API for video analysis, model: {}, videoUrl: {}",
                glmConfig.getModel(), videoUrl);

        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
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

        ChatCompletionResponse response = zhipuAiClient.chat().createChatCompletion(request);

        if (response.isSuccess() && response.getData() != null
                && response.getData().getChoices() != null
                && !response.getData().getChoices().isEmpty()) {

            Object content = response.getData().getChoices().get(0).getMessage().getContent();
            String result = content != null ? content.toString() : "";

            log.info("GLM API response received, length: {}", result.length());
            return result;
        } else {
            String errorMsg = response.getMsg() != null ? response.getMsg() : "Unknown error";
            log.error("GLM API call failed: {}", errorMsg);
            throw new RuntimeException("GLM API call failed: " + errorMsg);
        }
    }
}
