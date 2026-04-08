package com.videoai.common.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务消息 - Kafka消息体
 *
 * 面试重点：
 * 1. 为什么实现Serializable？
 *    Kafka消息需要序列化传输
 *    虽然JSON序列化不需要，但加上更保险
 *
 * 2. 消息体包含哪些必要信息？
 *    - taskId: 唯一标识，用于幂等消费
 *    - uploadId: 关联上传会话
 *    - userId: 用于配额控制、审计
 *    - videoUrl: 视频地址
 *    - timestamp: 消息产生时间，用于监控延迟
 *
 * 3. 为什么不把整个任务对象发过去？
 *    - 消息体要精简，减少网络传输
 *    - Worker自己查数据库获取最新状态
 *    - 避免消息体和数据库状态不一致
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     * 作为Kafka消息的key，保证同一任务的消息有序
     */
    private String taskId;

    /**
     * 上传会话ID
     */
    private String uploadId;

    /**
     * 用户ID
     * 用于：
     * 1. 按用户分区，保证同一用户任务顺序
     * 2. 用户级别限流
     * 3. 审计追踪
     */
    private Long userId;

    /**
     * 视频URL
     * Worker通过这个URL下载视频
     */
    private String videoUrl;

    /**
     * 视频时长（秒）
     * 用于预估成本和超时时间
     */
    private Integer videoDuration;

    /**
     * 重试次数
     * 消费者根据这个决定是否继续重试
     */
    private Integer retryCount;

    /**
     * 消息创建时间戳
     * 用于监控消息延迟
     * 面试点：为什么用Long而不是LocalDateTime？
     * 时间戳跨语言兼容性好，JSON序列化简单
     */
    private Long timestamp;

    /**
     * 优先级
     * 0: 最高（VIP用户）
     * 5: 普通优先级
     * 10: 最低（后台任务）
     */
    private Integer priority;

    /**
     * 分析类型
     * SUMMARY: 生成摘要
     * SCENE: 场景识别
     * OCR: 文字识别
     * FULL: 完整分析
     */
    private String analysisType;

    /**
     * 创建消息（便捷方法）
     */
    public static TaskMessage create(String taskId, Long userId, String videoUrl) {
        return TaskMessage.builder()
                .taskId(taskId)
                .userId(userId)
                .videoUrl(videoUrl)
                .retryCount(0)
                .timestamp(System.currentTimeMillis())
                .priority(5)
                .analysisType("FULL")
                .build();
    }

    /**
     * 增加重试次数
     */
    public TaskMessage incrementRetry() {
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
        this.timestamp = System.currentTimeMillis();
        return this;
    }
}
