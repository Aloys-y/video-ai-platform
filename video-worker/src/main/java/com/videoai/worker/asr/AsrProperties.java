package com.videoai.worker.asr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "analysis.asr")
@org.springframework.validation.annotation.Validated
public class AsrProperties {
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String model = "fun-asr-2025-11-07";
    @lombok.ToString.Exclude
    private String apiKey;
    @jakarta.validation.constraints.Min(1)
    private int requestTimeoutSeconds = 45;
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(60)
    private int pollIntervalSeconds = 5;
    @jakarta.validation.constraints.Min(1)
    private int maxWaitSeconds = 1800;
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(168)
    private int signedUrlHours = 24;
}
