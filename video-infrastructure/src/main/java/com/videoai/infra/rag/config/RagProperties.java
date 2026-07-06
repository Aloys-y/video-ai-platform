package com.videoai.infra.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "videoai.rag")
public class RagProperties {

    private boolean enabled = true;

    private boolean failOpen = true;

    private String knowledgeBase = "apex-default";

    private int topK = 12;

    private int finalTopK = 6;

    private double minScore = 0.72D;

    private int maxContextChars = 3500;

    private int chunkTargetChars = 1200;

    private int chunkMinChars = 800;

    private int chunkMaxChars = 1500;

    private int chunkOverlapChars = 100;

    private int dispatchBatchSize = 20;

    private int dispatchSendTimeoutSeconds = 10;
}
