package com.videoai.infra.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "videoai.rag.vector")
public class MilvusProperties {

    private String provider = "milvus";

    private String baseUrl = "http://localhost:19530";

    private String token = "";

    private String database = "default";

    private String collection = "apex_knowledge_chunk";

    private String metricType = "COSINE";

    private String indexType = "HNSW";

    private int connectTimeoutSeconds = 10;

    private int timeoutSeconds = 30;

    private int hnswM = 16;

    private int hnswEfConstruction = 200;

    private int searchEf = 64;
}
