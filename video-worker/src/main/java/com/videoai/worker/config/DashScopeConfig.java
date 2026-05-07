package com.videoai.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云DashScope配置
 * 支持 Qwen-VL 系列视频理解模型
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.dashscope")
public class DashScopeConfig {

    private String apiKey;
    private String model = "qwen3-vl-flash";
    private int maxTokens = 4096;
    private int timeout = 300;
    /** SDK连接超时（秒） */
    private int connectTimeout = 30;
    /** MinIO预签名URL过期时间（小时） */
    private int presignedUrlExpireHours = 2;
}
