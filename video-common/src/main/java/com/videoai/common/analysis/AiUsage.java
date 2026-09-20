package com.videoai.common.analysis;

import java.math.BigDecimal;

/** 数字由厂商适配器归一化；未知为null，原始JSON仅含usage。 */
public record AiUsage(Long inputTokens, Long outputTokens, BigDecimal audioSeconds, String rawJson) {
    public static AiUsage unknown() { return new AiUsage(null, null, null, null); }
}
