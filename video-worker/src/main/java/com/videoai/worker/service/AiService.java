package com.videoai.worker.service;

import com.videoai.common.rag.PromptEnvelope;
import com.videoai.worker.service.provider.AiProviderException;
import com.videoai.worker.service.provider.AiVideoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 视频分析服务（门面）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final AiVideoProvider aiVideoProvider;

    /**
     * 单次调用 AI，不在此层处理业务重试。
     */
    public String analyzeVideo(String videoUrl, PromptEnvelope promptEnvelope) {
        String fullPrompt = promptEnvelope.buildFullPrompt();

        log.info("Calling {} API", aiVideoProvider.getName());
        try {
            String result = aiVideoProvider.call(videoUrl, fullPrompt);
            log.info("{} API response received, length: {}", aiVideoProvider.getName(), result.length());
            return result;
        } catch (AiProviderException e) {
            throw new RuntimeException(aiVideoProvider.getName()
                    + " API call failed (retryable=" + e.isRetryable() + "): " + e.getMessage(), e);
        }
    }

    public int getPresignedUrlExpireHours() {
        return aiVideoProvider.getPresignedUrlExpireHours();
    }
}
