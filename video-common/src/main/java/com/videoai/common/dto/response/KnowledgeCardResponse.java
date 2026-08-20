package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class KnowledgeCardResponse {

    private String baseCode;
    private String cardCode;
    private String title;
    private String category;
    private String subjectCode;
    private List<String> aliases;
    private List<String> tags;
    private String contentMarkdown;
    private Boolean enabled;
    private Boolean timeless;
    private String versionTag;
    private String indexStatus;
    private String lastJobId;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime indexedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
