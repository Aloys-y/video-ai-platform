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

    /** 单张知识卡片最多进入最终上下文的分块数，避免相邻块挤占全部结果。 */
    private int maxChunksPerCard = 2;

    private double minScore = 0.72D;

    private int maxContextChars = 3500;

    private int chunkTargetChars = 1200;

    private int chunkMinChars = 800;

    private int chunkMaxChars = 1500;

    private int chunkOverlapChars = 100;

    private int dispatchBatchSize = 20;

    private int dispatchSendTimeoutSeconds = 10;

    /** 已标记 QUEUED 但未成功送达 Kafka 的任务，超过该时间后重新投递。 */
    private int queuedRecoveryTimeoutSeconds = 60;

    /** 长时间停留在 PROCESSING 的任务标记为失败，避免永久假运行。 */
    private int processingTimeoutSeconds = 1800;

    /** 全量重建允许更长执行时间，避免大知识库正常构建被误判超时。 */
    private int rebuildProcessingTimeoutSeconds = 21600;
}
