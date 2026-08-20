package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TaskRagContextResponse {

    private String taskId;
    private String baseCode;
    private String versionTag;
    private String queryText;
    private String retrievalMode;
    private Integer topK;
    private Integer hitCount;
    private Integer contextChars;
    private String status;
    private Integer latencyMs;
    private String snapshotJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
