package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务投递Outbox实体
 */
@Data
@TableName("task_outbox")
public class TaskOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String taskId;

    private String eventType;

    /**
     * 任务执行代次。字段名为兼容现有表结构保留 business_retry_no。
     */
    private Integer businessRetryNo;

    private String payload;

    private String status;

    private LocalDateTime availableAt;

    private Integer sendAttemptCount;

    private String lastError;

    private LocalDateTime sentAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
