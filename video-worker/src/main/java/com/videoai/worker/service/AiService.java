package com.videoai.worker.service;

import com.videoai.worker.service.provider.AiProviderException;
import com.videoai.worker.service.provider.AiVideoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI视频分析服务（门面）
 *
 * 通过 AiVideoProvider 接口解耦底层大模型厂商
 * 支持 Zhipu(GLM) / DashScope(Qwen-VL) 自由切换
 * 配置项: ai.provider = dashscope(默认) / zhipu
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final AiVideoProvider aiVideoProvider;

    /** 重试最大次数 */
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
     * 分析视频内容（含限流自动重试）
     *
     * @param videoUrl 视频公网URL
     * @param prompt   用户自定义提示词（可为null，使用默认）
     * @return AI返回的分析结果（JSON字符串）
     */
    public String analyzeVideo(String videoUrl, String prompt) {
        String userPrompt = (prompt != null && !prompt.isBlank()) ? prompt : DEFAULT_USER_PROMPT;
        String fullPrompt = SYSTEM_PROMPT + "\n\n" + userPrompt;
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Calling {} API, attempt: {}/{}",
                        aiVideoProvider.getName(), attempt + 1, MAX_RETRIES + 1);

                String result = aiVideoProvider.call(videoUrl, fullPrompt);
                log.info("{} API response received, length: {}", aiVideoProvider.getName(), result.length());
                return result;

            } catch (AiProviderException e) {
                if (e.isRetryable()) {
                    lastException = e;
                    if (attempt < MAX_RETRIES) {
                        int delay = RETRY_DELAYS_MS[attempt];
                        log.warn("{} API rate limited, retrying in {}ms, attempt: {}/{}",
                                aiVideoProvider.getName(), delay, attempt + 1, MAX_RETRIES);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("API call interrupted during retry", ie);
                        }
                        continue;
                    }
                    throw new RuntimeException(aiVideoProvider.getName()
                            + " API call failed after " + MAX_RETRIES + " retries", e);
                }
                throw new RuntimeException(aiVideoProvider.getName()
                        + " API call failed: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException(aiVideoProvider.getName()
                + " API call failed after " + MAX_RETRIES + " retries", lastException);
    }

    /**
     * 获取MinIO预签名URL过期时间（由当前Provider提供）
     */
    public int getPresignedUrlExpireHours() {
        return aiVideoProvider.getPresignedUrlExpireHours();
    }
}
