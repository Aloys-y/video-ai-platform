package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户角色枚举
 */
@Getter
@AllArgsConstructor
public enum UserRole {

    USER("USER", "普通用户", 100, 10000L),
    VIP("VIP", "VIP用户", 200, 50000L),
    ADMIN("ADMIN", "管理员", 1000, 1000000L);

    /**
     * 角色编码
     */
    private final String code;

    /**
     * 角色描述
     */
    private final String description;

    /**
     * 默认限流QPS
     */
    private final int defaultRateLimit;

    /**
     * 默认月度配额
     */
    private final long defaultQuota;

    public static UserRole fromCode(String code) {
        if (code == null) {
            return USER;
        }
        for (UserRole role : values()) {
            if (role.getCode().equals(code)) {
                return role;
            }
        }
        return USER;
    }
}
