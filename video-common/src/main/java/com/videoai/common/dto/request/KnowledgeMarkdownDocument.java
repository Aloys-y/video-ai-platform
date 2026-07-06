package com.videoai.common.dto.request;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeMarkdownDocument {

    private String fileName;

    private String contentMarkdown;
}
