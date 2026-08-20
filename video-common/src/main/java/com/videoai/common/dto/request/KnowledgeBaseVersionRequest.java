package com.videoai.common.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeBaseVersionRequest {

    @NotBlank(message = "versionTag不能为空")
    @Size(max = 64, message = "versionTag最长64字符")
    private String versionTag;
}
