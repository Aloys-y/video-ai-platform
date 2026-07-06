package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeMarkdownImportItemResponse {

    private String fileName;

    private String cardCode;

    private String title;

    private String category;

    private Boolean enabled;

    private Boolean timeless;

    private String indexStatus;

    private String lastJobId;

    private String status;

    private String message;
}
