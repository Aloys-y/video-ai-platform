package com.videoai.common.utils;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试退避工具
 */
public final class RetryBackoffUtil {

    private RetryBackoffUtil() {
    }

    public static LocalDateTime nextBusinessRetryAt(int retryNo) {
        long baseSeconds = switch (retryNo) {
            case 1 -> 30L;
            case 2 -> 120L;
            case 3 -> 600L;
            default -> 1800L;
        };
        return LocalDateTime.now().plusSeconds(withJitter(baseSeconds));
    }

    public static LocalDateTime nextDispatchRetryAt(int nextAttemptCount) {
        long baseSeconds = Math.min(5L * (1L << Math.max(0, nextAttemptCount - 1)), 120L);
        return LocalDateTime.now().plusSeconds(baseSeconds);
    }

    private static long withJitter(long baseSeconds) {
        double factor = ThreadLocalRandom.current().nextDouble(0.8D, 1.2D);
        long result = Math.round(baseSeconds * factor);
        return Math.max(1L, result);
    }
}
