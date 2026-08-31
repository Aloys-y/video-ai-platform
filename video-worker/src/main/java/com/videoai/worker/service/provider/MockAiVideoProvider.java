package com.videoai.worker.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 压测专用 AI Provider。
 *
 * <p>只模拟大模型调用的阻塞时间，不产生任何外部请求和 Token 费用。延迟分桶是确定性的，
 * 相同提示词总会落入同一档，方便不同并发参数之间复现实验。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock")
public class MockAiVideoProvider implements AiVideoProvider {

    @Value("${ai.mock.short-ratio:60}")
    private int shortRatio;

    @Value("${ai.mock.medium-ratio:30}")
    private int mediumRatio;

    @Value("${ai.mock.short-min-ms:200}")
    private long shortMinMs;

    @Value("${ai.mock.short-max-ms:400}")
    private long shortMaxMs;

    @Value("${ai.mock.medium-min-ms:800}")
    private long mediumMinMs;

    @Value("${ai.mock.medium-max-ms:1200}")
    private long mediumMaxMs;

    @Value("${ai.mock.long-min-ms:2400}")
    private long longMinMs;

    @Value("${ai.mock.long-max-ms:3000}")
    private long longMaxMs;

    @Value("${ai.mock.presigned-url-expire-hours:1}")
    private int presignedUrlExpireHours;

    @Override
    public String call(String videoUrl, String prompt) throws AiProviderException {
        validateConfiguration();

        int bucket = Math.floorMod(prompt.hashCode(), 100);
        DelayProfile profile;
        if (bucket < shortRatio) {
            profile = new DelayProfile("SHORT", shortMinMs, shortMaxMs);
        } else if (bucket < shortRatio + mediumRatio) {
            profile = new DelayProfile("MEDIUM", mediumMinMs, mediumMaxMs);
        } else {
            profile = new DelayProfile("LONG", longMinMs, longMaxMs);
        }

        long delayMs = deterministicDelay(prompt, profile.minMs(), profile.maxMs());
        log.info("Mock AI analysis started: profile={}, delayMs={}", profile.name(), delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Mock AI analysis interrupted", e, true);
        }

        return "{\"summary\":\"mock analysis completed\","
                + "\"profile\":\"" + profile.name() + "\","
                + "\"delayMs\":" + delayMs + "}";
    }

    @Override
    public int getPresignedUrlExpireHours() {
        return presignedUrlExpireHours;
    }

    @Override
    public String getName() {
        return "MockAI";
    }

    private long deterministicDelay(String prompt, long minMs, long maxMs) {
        if (maxMs <= minMs) {
            return minMs;
        }
        long range = maxMs - minMs + 1;
        int mixedHash = 31 * prompt.hashCode() + 17;
        return minMs + Math.floorMod((long) mixedHash, range);
    }

    private void validateConfiguration() {
        if (shortRatio < 0 || mediumRatio < 0 || shortRatio + mediumRatio > 100) {
            throw new IllegalStateException("ai.mock ratios must be non-negative and sum to at most 100");
        }
        if (shortMinMs < 0 || mediumMinMs < 0 || longMinMs < 0
                || shortMaxMs < shortMinMs
                || mediumMaxMs < mediumMinMs
                || longMaxMs < longMinMs) {
            throw new IllegalStateException("ai.mock delay ranges are invalid");
        }
    }

    private record DelayProfile(String name, long minMs, long maxMs) {
    }
}
