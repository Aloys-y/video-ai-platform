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

    /** 是否把分块标题路径编码进向量；消融实验后默认关闭，元数据中的 heading_path 始终保留。 */
    private boolean embeddingHeadingPathEnabled = false;

    /** 向量召回与内存 BM25 并行召回后使用 RRF 融合；默认关闭，先通过 bench 验证。 */
    private boolean hybridRetrievalEnabled = false;

    /** 仅当通过阈值和去重后的向量结果不足 finalTopK 时，才用 BM25 按原顺序补空位。 */
    private boolean hybridConditionalRescueEnabled = false;

    /** 条件补位候选仍需达到的宽松语义下限，避免纯词法命中直接进入上下文。 */
    private double hybridConditionalRescueMinDenseScore = 0.60D;

    /** 单次查询最多补入的 BM25 chunk 数。 */
    private int hybridConditionalRescueMaxChunks = 1;

    /** 是否允许纯 BM25 候选进入融合并集；关闭时 BM25 只重排已被向量召回的候选。 */
    private boolean hybridLexicalUnionEnabled = false;

    /** BM25 返回的候选数量。 */
    private int lexicalTopK = 20;

    /** RRF 平滑常数；值越大，头部名次之间的差异越平缓。 */
    private int rrfK = 60;

    private double denseRrfWeight = 1.0D;

    private double lexicalRrfWeight = 1.0D;

    /** 内存词法索引刷新间隔，避免每次请求扫描 MySQL。 */
    private int lexicalIndexTtlSeconds = 300;

    private int topK = 20;

    private int finalTopK = 3;

    /** 单张知识卡片最多进入最终上下文的分块数，避免相邻块挤占全部结果。 */
    private int maxChunksPerCard = 2;

    /** 中文英雄 bench 首轮实测值；后续仍需扩大独立测试集验证泛化。 */
    private double minScore = 0.62D;

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
