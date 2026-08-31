package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class LegendKnowledgeAuditResponse {

    private String baseCode;
    private String versionTag;
    private String collectionName;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimension;
    private String embeddingTextType;
    private LocalDateTime generatedAt;
    private Integer totalCards;
    private Integer enabledCards;
    private Integer mobileCards;
    private Integer gameplayCards;
    private Integer mysqlChunkCount;
    private Long milvusVectorCount;
    private Integer orphanVectorCards;
    private Long orphanVectorCount;
    private Map<String, Long> orphanVectorsByCard;
    private Integer matchedCards;
    private Integer mismatchCards;
    private List<LegendCardAuditItem> cards;
}
