package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_card")
public class KnowledgeCard {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String baseCode;

    private String cardCode;

    private String title;

    private String category;

    private String subjectCode;

    private String aliases;

    private String tags;

    private String contentMarkdown;

    private Integer enabled;

    private Integer timeless;

    private String versionTag;

    private String indexStatus;

    private String lastJobId;

    private String createdBy;

    private String updatedBy;

    private LocalDateTime indexedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
