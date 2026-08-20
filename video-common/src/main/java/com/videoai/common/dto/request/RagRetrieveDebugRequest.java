package com.videoai.common.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RagRetrieveDebugRequest {

    @NotBlank(message = "query不能为空")
    @Size(max = 2000, message = "query最长2000字符")
    private String query;
}
