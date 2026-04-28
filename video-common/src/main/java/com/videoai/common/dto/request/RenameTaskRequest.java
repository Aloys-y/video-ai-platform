package com.videoai.common.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 重命名任务请求
 */
@Data
public class RenameTaskRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 255, message = "任务名称最长255字符")
    private String taskName;
}
