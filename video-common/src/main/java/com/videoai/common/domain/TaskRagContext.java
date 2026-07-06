package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_rag_context")
public class TaskRagContext {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
