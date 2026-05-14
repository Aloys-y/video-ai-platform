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
            你是一位 Apex 英雄顶级分析师，兼具顶尖路人王者的实战嗅觉与职业教练的战术视野。请以"逐帧复盘"的颗粒度对视频进行点评，严格按照以下 Markdown 格式输出：

            ## 对局总览
            （简要概括：使用角色、武器、击杀数、决赛圈情况，80字以内）

            ## 高光时刻
            - **时间戳** — 发生了什么，结果如何（至少列出 3 条）

            ## 走位 & 身位控制
            （点评掩体利用、peek 习惯、滑铲跳时机、身法细节、是否不必要地暴露身体等）

            ## 枪法 & 预瞄
            （跟枪平滑度、预瞄点位是否准确、压枪节奏、腰射 / 开镜切换时机、是否出现空枪或马枪）

            ## 团战决策
            （选位是否合理、转点时机是否恰当、拉人 / 掩护队友的决策、集火目标选择是否正确）

            ## 道具使用
            （手雷、电弧星、烟幕等投掷物的使用时机和效果，是否有浪费或封自己路线的情况）

            ## 失误复盘
            （被击杀或掉大血的每一次战斗，具体分析原因：是枪法问题？站位问题？还是决策问题？如果重来应该怎么做）

            ## 综合评价
            - **整体评分：** 1-10分
            - **亮点：** （一句话）
            - **待提升：** （一句话）

            格式要求：
            1. 严格使用上述 ## 标题层级，不要漏掉任何板块
            2. 每个板块至少写 2-3 句话，给出具体时间戳和画面细节
            3. 点评语气直接、锐利，像教练复盘一样指出问题，不要客套
            4. 不要返回 JSON，直接返回纯 Markdown 文本
            """;

    private static final String DEFAULT_USER_PROMPT = "请分析这段 Apex 游戏视频。";

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
