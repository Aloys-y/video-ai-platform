package com.videoai.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云DashScope配置
 * 当前使用 Qwen3.7-Plus 兼容接口进行片段视频理解
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.dashscope")
public class DashScopeConfig {

    private String apiKey;
    private String model = "qwen3.7-plus";
    private int maxTokens = 4096;
    private int timeout = 300;
    /** HTTP连接超时（秒） */
    private int connectTimeout = 30;
    /** MinIO预签名URL过期时间（小时） */
    private int presignedUrlExpireHours = 2;
}
