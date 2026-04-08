package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务状态枚举
 *
 * 面试重点：为什么用枚举而不是常量类？
 * 1. 类型安全，编译期检查
 * 2. 可以封装行为（如状态转换逻辑）
 * 3. 支持switch语句
 * 4. 自带name()和ordinal()
 *
 * 状态机设计：
 * PENDING -> QUEUED -> PROCESSING -> COMPLETED
 *                 \-> FAILED -> RETRYING -> PROCESSING
 */
@Getter
@AllArgsConstructor
public enum TaskStatus {

    /**
     * 待处理 - 任务刚创建
     */
    PENDING("PENDING", "待处理", 0),

    /**
     * 已入队 - 消息已发送到Kafka
     * 面试点：为什么需要这个状态？
     * 区分"创建成功"和"已进入处理队列"，便于排查消息丢失问题
     */
    QUEUED("QUEUED", "已入队", 1),

    /**
     * 处理中 - Worker正在处理
     */
    PROCESSING("PROCESSING", "处理中", 2),

    /**
     * 处理成功
     */
    COMPLETED("COMPLETED", "处理成功", 3),

    /**
     * 处理失败 - 等待重试
     */
    FAILED("FAILED", "处理失败", 4),

    /**
     * 重试中 - 从失败恢复
     */
    RETRYING("RETRYING", "重试中", 5),

    /**
     * 已取消 - 用户主动取消
     */
    CANCELLED("CANCELLED", "已取消", 6),

    /**
     * 最终失败 - 重试次数耗尽
     */
    DEAD("DEAD", "最终失败", 7);

    /**
     * 状态码 - 存储到数据库的值
     */
    private final String code;

    /**
     * 状态描述 - 用于展示
     */
    private final String description;

    /**
     * 状态顺序 - 用于排序和比较
     */
    private final int order;

    /**
     * 判断是否为终态
     * 面试点：为什么需要判断终态？
     * 终态的任务不能再被修改，避免无效操作
     */
    public boolean isFinalState() {
        return this == COMPLETED || this == CANCELLED || this == DEAD;
    }

    /**
     * 判断是否允许转换到目标状态
     * 状态机核心：控制状态流转的合法性
     */
    public boolean canTransitionTo(TaskStatus target) {
        if (this == target) {
            return true; // 相同状态允许（幂等）
        }

        return switch (this) {
            case PENDING -> target == QUEUED || target == CANCELLED;
            case QUEUED -> target == PROCESSING || target == CANCELLED;
            case PROCESSING -> target == COMPLETED || target == FAILED || target == CANCELLED;
            case FAILED -> target == RETRYING || target == DEAD;
            case RETRYING -> target == PROCESSING || target == FAILED || target == DEAD;
            case COMPLETED, CANCELLED, DEAD -> false; // 终态不可转换
        };
    }

    /**
     * 根据code获取枚举
     * 面试点：为什么用stream而不是for循环？
     * 1. 代码更简洁
     * 2. 函数式编程风格
     * 3. 性能差异可忽略（枚举数量少）
     */
    public static TaskStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        return java.util.Arrays.stream(values())
                .filter(status -> status.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid TaskStatus code: " + code));
    }
}
