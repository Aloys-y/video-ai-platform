package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeIndexJobResponse {

    private String jobId;
    private String baseCode;
    private String jobType;
    private String cardCode;
    private String status;
    private Integer totalChunks;
    private Integer successChunks;
    private Integer failedChunks;
    private String errorMessage;
    private String createdBy;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
