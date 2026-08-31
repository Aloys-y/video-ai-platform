package com.videoai.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器跨域访问配置。
 *
 * <p>使用 ConfigurationProperties 绑定 YAML 数组，避免 @Value 无法读取
 * {@code allowed-origins[0..n]} 时静默回退到不完整的默认值。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "videoai.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:8080",
            "http://127.0.0.1:8080"
    ));
}
