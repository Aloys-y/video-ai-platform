package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 用户实体
 *
 * 面试重点：
 * 1. API Key设计：带环境前缀的格式，便于识别环境
 * 2. 敏感字段：api_secret加密存储，不明文
 * 3. 软删除：status字段而非物理删除
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务用户ID
     * 格式：usr_xxxxxxxxxxxxxxxx
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 邮箱
     */
    private String email;

    /**
     * API Key
     * 格式：前缀 + 随机字符串
     * 面试点：为什么用前缀？
     * 1. 便于识别是生产还是测试环境
     * 2. 便于日志脱敏（只显示前缀）
     * 3. 行业标准（Stripe、OpenAI都这么做）
     */
    private String apiKey;

    /**
     * API Secret（加密存储）
     * 用于签名验证（可选，暂不实现）
     */
    @JsonIgnore
    @ToString.Exclude
    private String apiSecret;

    /**
     * 角色
     * USER: 普通用户
     * VIP: VIP用户（更高配额）
     * ADMIN: 管理员
     */
    private String role;

    /**
     * 状态
     * 1: 正常
     * 0: 禁用
     */
    private Integer status;

    /**
     * 用户级限流阈值（QPS）
     * 默认100，VIP可更高
     */
    private Integer rateLimit;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // ==================== 业务方法 ====================

    /**
     * 判断用户是否可用
     */
    public boolean isEnabled() {
        return status != null && status == 1;
    }

    /**
     * 判断是否为管理员
     */
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /**
     * 判断是否为VIP
     */
    public boolean isVip() {
        return "VIP".equals(role) || "ADMIN".equals(role);
    }

    /**
     * 获取脱敏后的API Key
     * 只显示前8位和后4位
     * 例：sk_live_***xyz
     */
    public String getMaskedApiKey() {
        if (apiKey == null || apiKey.length() < 12) {
            return apiKey;
        }
        return apiKey.substring(0, 8) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
