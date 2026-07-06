package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_index_job")
public class KnowledgeIndexJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String jobId;

    private String baseCode;

    private String jobType;

    private String cardCode;

    private String status;

    private String payloadJson;

    private Integer totalChunks;

    private Integer successChunks;

    private Integer failedChunks;

    private String errorMessage;

    private String createdBy;

    private LocalDateTime queuedAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
