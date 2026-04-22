package com.videoai.worker.config;

import ai.z.openapi.ZhipuAiClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智谱AI GLM配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.glm")
public class GlmConfig {

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
