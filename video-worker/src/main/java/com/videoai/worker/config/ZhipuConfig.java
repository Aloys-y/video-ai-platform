package com.videoai.worker.config;

import ai.z.openapi.ZhipuAiClient;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智谱AI配置（仅 ai.provider=zhipu 时激活）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.zhipu")
@ConditionalOnProperty(name = "ai.provider", havingValue = "zhipu")
public class ZhipuConfig {

    private String apiKey;
    private String model = "glm-4.6v-flash";
    private int maxTokens = 4096;
    private int timeout = 120;
    private int presignedUrlExpireHours = 2;

    @Bean
    public ZhipuAiClient zhipuAiClient() {
        return ZhipuAiClient.builder()
                .ofZHIPU()
                .apiKey(apiKey)
                .build();
    }
}
