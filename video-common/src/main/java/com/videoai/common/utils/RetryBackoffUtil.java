package com.videoai.common.utils;

import java.time.LocalDateTime;
/**
 * Outbox 消息投递退避工具。
 * 这里只重试 Kafka 投递，不触发 AI 业务重试。
 */
public final class RetryBackoffUtil {

    private RetryBackoffUtil() {
    }

    public static LocalDateTime nextDispatchRetryAt(int nextAttemptCount) {
        long baseSeconds = Math.min(5L * (1L << Math.max(0, nextAttemptCount - 1)), 120L);
        return LocalDateTime.now().plusSeconds(baseSeconds);
    }
}
