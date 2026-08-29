package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LegendCardAuditItem {

    private String cardCode;
    private String title;
    private Boolean enabled;
    private String indexStatus;
    private String versionTag;
    private LocalDateTime indexedAt;
    private String platform;
    private String knowledgeType;
    private String contentSha256;
    private Integer contentChars;
    private Integer mysqlChunkCount;
    private Long milvusVectorCount;
    private String coverageStatus;
}
