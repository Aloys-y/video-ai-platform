package com.videoai.infra.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "videoai.rag.embedding")
public class OpenAiEmbeddingProperties {

    private String provider = "dashscope";

    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";

    private String apiKey = "";

    private String model = "text-embedding-v3";

    private int dimension = 1024;

    private int connectTimeoutSeconds = 10;

    private int timeoutSeconds = 30;

    @NestedConfigurationProperty
    private DashScope dashscope = new DashScope();

    @Data
    public static class DashScope {

        private String textType = "document";
    }
}
