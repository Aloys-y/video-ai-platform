package com.videoai.common.rag;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RetrievalHit {

    private String vectorId;
    private String cardCode;
    private String title;
    private String category;
    private String headingPath;
    private String contentText;

    /** 对外兼容字段：始终保留原始向量相似度，便于继续分析 minScore。 */
    private double score;

    /** 混合检索调试字段；纯向量模式下只有 denseScore/denseRank。 */
    private Double denseScore;
    private Double lexicalScore;
    private Double fusionScore;
    private Integer denseRank;
    private Integer lexicalRank;
}
