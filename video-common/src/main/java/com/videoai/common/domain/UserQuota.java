package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户配额实体
 *
 * 面试重点：
 * 1. 为什么设计日配额和月配额两级？
 *    - 月配额：成本控制，防止用户超支
 *    - 日配额：防止突发流量，保护系统
 *
 * 2. 配额扣减的并发问题如何解决？
 *    - 使用Redis原子操作（INCRBY）
 *    - 数据库层面使用乐观锁
 *
 * 3. 为什么需要重置时间字段？
 *    - 定时任务根据这个时间判断是否需要重置
 *    - 支持不同时区用户
 */
@Data
@TableName("user_quota")
public class UserQuota {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 月度Token配额
     * 默认10000 tokens，约等于5分钟视频
     */
    private Long quotaMonthly;

    /**
     * 已使用月度配额
     */
    private Long usedMonthly;

    /**
     * 每日Token配额
     * 默认500 tokens，约等于15秒视频
     */
    private Long quotaDaily;

    /**
     * 已使用每日配额
     */
    private Long usedDaily;

    /**
     * 每日配额重置时间
     * 每天的0点重置usedDaily
     */
    private LocalDateTime resetDailyAt;

    /**
     * 月度配额重置时间
     * 每月1号0点重置usedMonthly
     */
    private LocalDateTime resetMonthlyAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // ==================== 业务方法 ====================

    /**
     * 获取剩余月度配额
     */
    public Long getRemainingMonthly() {
        if (quotaMonthly == null || usedMonthly == null) {
            return quotaMonthly;
        }
        return Math.max(0, quotaMonthly - usedMonthly);
    }

    /**
     * 获取剩余每日配额
     */
    public Long getRemainingDaily() {
        if (quotaDaily == null || usedDaily == null) {
            return quotaDaily;
        }
        return Math.max(0, quotaDaily - usedDaily);
    }

    /**
     * 检查是否有足够配额
     */
    public boolean hasEnoughQuota(Long requiredTokens) {
        return getRemainingDaily() >= requiredTokens
            && getRemainingMonthly() >= requiredTokens;
    }

    /**
     * 检查每日配额是否需要重置
     */
    public boolean needResetDaily() {
        if (resetDailyAt == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(resetDailyAt);
    }

    /**
     * 检查月度配额是否需要重置
     */
    public boolean needResetMonthly() {
        if (resetMonthlyAt == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(resetMonthlyAt);
    }

    /**
     * 重置每日配额
     */
    public void resetDaily() {
        this.usedDaily = 0L;
        // 设置下次重置时间为明天0点
        this.resetDailyAt = LocalDateTime.now()
                .plusDays(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
    }

    /**
     * 重置月度配额
     */
    public void resetMonthly() {
        this.usedMonthly = 0L;
        // 设置下次重置时间为下月1号0点
        this.resetMonthlyAt = LocalDateTime.now()
                .plusMonths(1)
                .withDayOfMonth(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
    }

    /**
     * 扣减配额
     * @param tokens 扣减的token数
     * @return 是否扣减成功
     */
    public boolean deduct(Long tokens) {
        if (!hasEnoughQuota(tokens)) {
            return false;
        }
        this.usedDaily += tokens;
        this.usedMonthly += tokens;
        return true;
    }
}
