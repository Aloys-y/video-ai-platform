package com.videoai.infra.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.AiUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.*;

/** 普通标价、不扣优惠；仅支持已配置的计量单位和输入阶梯。 */
@Component
@RequiredArgsConstructor
public class AiCostCalculator {
    private final AiPricingProperties properties;
    private final ObjectMapper json;
    public record Cost(BigDecimal amount, String unknownReason) {}

    public String snapshot(String model) {
        var rule = properties.getModels().get(model);
        if (rule == null) return null;
        validate(rule);
        try { return json.writeValueAsString(rule); }
        catch (Exception e) { throw new IllegalArgumentException("价格配置不可序列化"); }
    }

    public Cost calculate(String snapshot, AiUsage usage) {
        if (usage == null) usage = AiUsage.unknown();
        if (negative(usage.inputTokens()) || negative(usage.outputTokens())
                || usage.audioSeconds() != null && (usage.audioSeconds().signum() < 0 || usage.audioSeconds().scale() > 3))
            return new Cost(null, "INVALID_USAGE");
        if (snapshot == null) return new Cost(null, "MISSING_PRICE");
        try {
            var rule = json.readValue(snapshot, AiPricingProperties.Rule.class);
            validate(rule);
            if (rule.getUnit().equals("AUDIO_SECOND")) {
                return usage.audioSeconds() == null ? new Cost(null, "MISSING_USAGE")
                        : known(usage.audioSeconds().multiply(rule.getAudioRate()));
            }
            if (usage.inputTokens() == null || rule.getUnit().equals("TOKEN") && usage.outputTokens() == null)
                return new Cost(null, "MISSING_USAGE");
            for (var tier : rule.getTiers()) if (usage.inputTokens() <= tier.getMaxInputTokens()) {
                var amount = BigDecimal.valueOf(usage.inputTokens()).multiply(tier.getInputRate());
                if (rule.getUnit().equals("TOKEN")) amount = amount.add(BigDecimal.valueOf(usage.outputTokens()).multiply(tier.getOutputRate()));
                return known(amount.movePointLeft(6));
            }
            return new Cost(null, "INVALID_USAGE");
        } catch (Exception e) { return new Cost(null, "MISSING_PRICE"); }
    }
    private static boolean negative(Long v) { return v != null && v < 0; }
    private static Cost known(BigDecimal value) {
        value = value.setScale(10, RoundingMode.HALF_UP);
        return value.precision() > 20 ? new Cost(null, "INVALID_USAGE") : new Cost(value, null);
    }
    private static void validate(AiPricingProperties.Rule rule) {
        if (rule.getProvider() == null || rule.getProvider().isBlank() || rule.getRegion() == null
                || rule.getRegion().isBlank() || rule.getVersion() == null || rule.getVersion().isBlank())
            throw new IllegalArgumentException("价格缺少来源信息");
        if ("AUDIO_SECOND".equals(rule.getUnit())) {
            if (rule.getAudioRate() == null || rule.getAudioRate().signum() < 0) throw new IllegalArgumentException("无效ASR价格");
            return;
        }
        if (!"TOKEN".equals(rule.getUnit()) && !"INPUT_TOKEN".equals(rule.getUnit())) throw new IllegalArgumentException("未知计价单位");
        long previous = 0;
        if (rule.getTiers().isEmpty()) throw new IllegalArgumentException("缺少价格档位");
        for (var tier : rule.getTiers()) {
            if (tier.getMaxInputTokens() <= previous || tier.getInputRate() == null || tier.getInputRate().signum() < 0
                    || tier.getOutputRate() == null || tier.getOutputRate().signum() < 0)
                throw new IllegalArgumentException("无效价格档位");
            previous = tier.getMaxInputTokens();
        }
    }
}
