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

    /** 是否在原始查询后追加通用 Apex 术语；消融实验表明固定追加会稀释实体查询。 */
    private boolean queryExpansionEnabled = false;

    /** 中文英雄名和玩家俗称的定向实体增强；与通用查询扩展独立，便于真实 A/B。 */
    private boolean legendAliasEnhancementEnabled = true;

    /** PC 英雄知识过滤开关。语料已物理清洗，开启后只需约束 LEGEND 类别。 */
    private boolean legendPcGameplayFilterEnabled = true;

    /** 仅供隔离集合实验使用，默认禁止通过管理接口批量构建影子索引。 */
    private boolean shadowIndexBuildEnabled = false;

    private int topK = 20;

    private int finalTopK = 3;

    /** 单张知识卡片最多进入最终上下文的分块数，避免相邻块挤占全部结果。 */
    private int maxChunksPerCard = 2;

    /** LEGEND 开发集正负样本标定阈值；扩大知识类别或更换 Embedding 后必须重测。 */
    private double minScore = 0.61D;

    private int maxContextChars = 3500;

    private int chunkTargetChars = 1200;

    private int chunkMinChars = 800;

    private int chunkMaxChars = 1500;

    private int chunkOverlapChars = 100;

    /** 每轮从 MySQL 抢占执行的知识索引任务数。 */
    private int scanBatchSize = 20;

    /** 长时间停留在 PROCESSING 的任务标记为失败，避免永久假运行。 */
    private int processingTimeoutSeconds = 1800;

    /** 全量重建允许更长执行时间，避免大知识库正常构建被误判超时。 */
    private int rebuildProcessingTimeoutSeconds = 21600;
}
