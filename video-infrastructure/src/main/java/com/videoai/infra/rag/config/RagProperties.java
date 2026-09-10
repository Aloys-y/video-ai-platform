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

    /** 是否对原始向量 TopK 做查询-文档交叉编码重排。启用时优先于混合检索实验分支。 */
    private boolean rerankEnabled = true;

    /** DashScope OpenAI 兼容重排接口；API Key 为空时复用 Embedding 的 Key。 */
    private String rerankBaseUrl = "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";

    private String rerankApiKey = "";

    private String rerankModel = "qwen3-rerank";

    /** 开发集实测平台区间为 0.59～0.63，取下界优先保留召回。 */
    private double rerankMinScore = 0.59D;

    private int rerankConnectTimeoutMillis = 1000;

    private int rerankTimeoutMillis = 2000;

    /** 外部重排失败时回退到原纯向量阈值链路，不阻断视频分析。 */
    private boolean rerankFailOpen = true;

    private String rerankInstruction = "Given an Apex Legends gameplay question, retrieve passages "
            + "that directly answer the question. Prefer exact ability mechanics over passages "
            + "that are only topically related.";

    /** 单张知识卡片最多进入最终上下文的分块数，避免相邻块挤占全部结果。 */
    private int maxChunksPerCard = 2;

    /** 纯向量模式及 Reranker 失败回退时使用的相似度门槛。 */
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
