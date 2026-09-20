package com.videoai.infra.cost;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

@Data
@Component
@PropertySource("classpath:ai-pricing.properties")
@ConfigurationProperties(prefix = "ai.pricing")
public class AiPricingProperties {
    private Map<String, Rule> models = new HashMap<>();
    @Data public static class Rule {
        private String provider;
        private String region;
        private String version;
        private String unit; // TOKEN / AUDIO_SECOND / INPUT_TOKEN
        private BigDecimal audioRate;
        private List<Tier> tiers = new ArrayList<>();
    }
    @Data public static class Tier {
        private long maxInputTokens;
        private BigDecimal inputRate;
        private BigDecimal outputRate;
    }
}
