package com.videoai.infra.kafka.topic;

/**
 * Kafka Topic定义
 *
 * 面试重点：
 * 1. 为什么分多个Topic而不是一个？
 *    - 不同类型消息有不同的消费者
 *    - 隔离故障：一个消费者挂了不影响其他
 *    - 不同的分区策略
 *
 * 2. Topic命名规范？
 *    - 业务线.模块.动作
 *    - 例：videoai.task.create
 *
 * 3. 为什么用常量而不是配置文件？
 *    - Topic名称变更很少
 *    - 代码中使用更方便
 */
public final class TopicConstant {

    private TopicConstant() {}

    /**
     * 任务Topic
     * 消息类型：TaskMessage
     * 分区数：10（根据并发量调整）
     * 副本数：3
     */
    public static final String TASK_TOPIC = "videoai.task.analyze";

    /**
     * 任务结果Topic
     * 消息类型：ResultMessage
     * 用于通知前端任务完成
     */
    public static final String RESULT_TOPIC = "videoai.task.result";

    /**
     * 死信Topic
     * 重试失败的消息发送到这里
     * 需要人工处理或定时重试
     */
    public static final String DEAD_LETTER_TOPIC = "videoai.task.dead";

    /**
     * 任务事件Topic
     * 用于记录任务状态变更事件
     * 可接入ELK做监控分析
     */
    public static final String TASK_EVENT_TOPIC = "videoai.task.event";

    // ==================== Consumer Group ====================

    /**
     * 消费者组：视频分析Worker
     * 同一组内的消费者共同消费消息（负载均衡）
     */
    public static final String WORKER_GROUP = "videoai-worker-group";

    /**
     * 消费者组：结果通知
     */
    public static final String NOTIFIER_GROUP = "videoai-notifier-group";

    /**
     * 消费者组：事件监控
     * 用于收集指标和日志
     */
    public static final String MONITOR_GROUP = "videoai-monitor-group";
}
