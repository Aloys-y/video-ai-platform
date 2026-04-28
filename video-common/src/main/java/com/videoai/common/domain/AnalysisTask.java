package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.videoai.common.enums.TaskStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分析任务实体
 *
 * 面试重点：
 * 1. 为什么task_id和upload_id分开？
 *    - 一个上传可能产生多个任务（不同分析类型）
 *    - 解耦上传和分析的生命周期
 *
 * 2. 为什么要记录retry_count？
 *    - 控制重试次数，避免无限重试
 *    - 监控告警：频繁重试说明有问题
 *
 * 3. 为什么用JSON存储result？
 *    - AI返回结果是结构化的，灵活变化
 *    - 避免结果字段变更导致改表结构
 */
@Data
@TableName("analysis_task")
public class AnalysisTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务ID（业务主键）
     * 格式：task_{timestamp}_{random}
     */
    private String taskId;

    /**
     * 任务名称（用户自定义）
     */
    private String taskName;

    /**
     * 关联的上传会话ID
     */
    private String uploadId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 视频文件URL
     */
    private String videoUrl;

    /**
     * 视频时长（秒）
     */
    private Integer videoDuration;

    // ==================== 状态管理 ====================

    /**
     * 任务状态
     */
    private String status;

    /**
     * 处理进度（0-100）
     * 面试点：为什么需要进度？
     * 1. 用户体验：让用户知道处理到哪了
     * 2. 监控：长时间卡在某个进度的任务可能有问题
     */
    private Integer progress;

    // ==================== 重试机制 ====================

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     * 默认3次，可配置
     */
    private Integer maxRetry;

    /**
     * 错误信息
     * 记录最后一次失败的原因
     */
    private String errorMessage;

    // ==================== AI分析结果 ====================

    /**
     * 抽取的帧数
     */
    private Integer frameCount;

    /**
     * 使用的AI模型
     * 如：claude-3-opus, gpt-4-vision
     */
    private String aiModel;

    /**
     * 消耗的Token数
     * 用于成本统计和配额扣减
     */
    private Long tokensUsed;

    /**
     * 分析结果（JSON格式）
     * 面试点：为什么用String而不是JSON类型？
     * MyBatis-Plus对JSON类型支持需要额外配置
     * String通用性更好
     */
    private String result;

    /**
     * 视频摘要
     * AI生成的简短描述
     */
    private String summary;

    // ==================== 时间记录 ====================

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 开始处理时间
     * 用于计算处理耗时
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    // ==================== 业务方法 ====================

    /**
     * 获取状态枚举
     */
    public TaskStatus getStatusEnum() {
        return status != null ? TaskStatus.fromCode(status) : null;
    }

    /**
     * 设置状态
     */
    public void setStatusEnum(TaskStatus status) {
        this.status = status.getCode();
    }

    /**
     * 是否可以重试
     */
    public boolean canRetry() {
        if (retryCount == null || maxRetry == null) {
            return false;
        }
        return retryCount < maxRetry;
    }

    /**
     * 增加重试次数
     */
    public void incrementRetry() {
        if (retryCount == null) {
            retryCount = 0;
        }
        retryCount++;
    }

    /**
     * 获取处理耗时（毫秒）
     */
    public Long getProcessingTimeMs() {
        if (startedAt == null || completedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, completedAt).toMillis();
    }

    /**
     * 是否为终态
     */
    public boolean isFinalState() {
        TaskStatus statusEnum = getStatusEnum();
        return statusEnum != null && statusEnum.isFinalState();
    }
}
