package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KnowledgeMarkdownImportResponse {

    private Integer totalFiles;

    private Integer successCount;

    private Integer failedCount;

    private List<KnowledgeMarkdownImportItemResponse> items;
}
