package com.videoai.common.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeCardUpsertRequest {

    @NotBlank(message = "cardCode不能为空")
    @Size(max = 64, message = "cardCode最长64字符")
    private String cardCode;

    @NotBlank(message = "title不能为空")
    @Size(max = 255, message = "title最长255字符")
    private String title;

    @NotBlank(message = "category不能为空")
    @Size(max = 32, message = "category最长32字符")
    private String category;

    @Size(max = 64, message = "subjectCode最长64字符")
    private String subjectCode;

    private List<String> aliases;

    private List<String> tags;

    @NotBlank(message = "contentMarkdown不能为空")
    private String contentMarkdown;

    @NotNull(message = "enabled不能为空")
    private Boolean enabled;

    @NotNull(message = "timeless不能为空")
    private Boolean timeless;
}
