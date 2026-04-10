package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码枚举
 *
 * 面试重点：为什么需要统一错误码？
 * 1. 前后端对接有标准
 * 2. 问题排查快（错误码唯一）
 * 3. 国际化支持（根据错误码找对应语言文案）
 *
 * 错误码设计规范：
 * - 1xxxx: 系统级错误
 * - 2xxxx: 业务级错误
 * - 3xxxx: 第三方服务错误
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ==================== 成功 ====================
    SUCCESS(0, "操作成功"),

    // ==================== 系统级错误 1xxxx ====================
    SYSTEM_ERROR(10000, "系统繁忙，请稍后重试"),
    PARAM_ERROR(10001, "参数错误"),
    PARAM_MISSING(10002, "缺少必要参数"),
    REQUEST_METHOD_NOT_SUPPORTED(10003, "请求方法不支持"),

    // ==================== 上传相关 2xxxx ====================
    UPLOAD_SESSION_NOT_FOUND(20001, "上传会话不存在"),
    UPLOAD_SESSION_EXPIRED(20002, "上传会话已过期"),
    UPLOAD_CHUNK_INDEX_ERROR(20003, "分片索引错误"),
    UPLOAD_CHUNK_SIZE_ERROR(20004, "分片大小不正确"),
    UPLOAD_FILE_TYPE_NOT_SUPPORT(20005, "文件类型不支持"),
    UPLOAD_FILE_TOO_LARGE(20006, "文件大小超出限制"),
    UPLOAD_CHUNK_UPLOADING(20007, "分片正在上传中"),
    UPLOAD_CHUNK_UPLOAD_FAILED(20008, "分片上传失败"),
    UPLOAD_MERGE_FAILED(20009, "文件合并失败"),

    // ==================== 任务相关 21xxx ====================
    TASK_NOT_FOUND(21001, "任务不存在"),
    TASK_ALREADY_EXISTS(21002, "任务已存在"),
    TASK_STATUS_ERROR(21003, "任务状态不正确"),
    TASK_RETRY_EXHAUSTED(21004, "任务重试次数已耗尽"),
    TASK_PROCESSING(21005, "任务正在处理中"),
    TASK_NOT_FOUND_BY_VIDEO(21006, "未找到对应的视频任务"),

    // ==================== 配额相关 22xxx ====================
    QUOTA_EXCEEDED(22001, "配额不足"),
    QUOTA_DAILY_EXCEEDED(22002, "每日配额已用完"),
    QUOTA_MONTHLY_EXCEEDED(22003, "每月配额已用完"),

    // ==================== 用户相关 23xxx ====================
    USER_NOT_FOUND(23001, "用户不存在"),
    USER_FORBIDDEN(23002, "用户被禁止"),
    USERNAME_EXISTS(23003, "用户名已存在"),
    EMAIL_EXISTS(23004, "邮箱已被注册"),
    INVALID_CREDENTIALS(23005, "邮箱或密码错误"),

    // ==================== 第三方服务错误 3xxxx ====================
    AI_SERVICE_ERROR(30001, "AI服务异常"),
    AI_SERVICE_TIMEOUT(30002, "AI服务超时"),
    AI_SERVICE_QUOTA_EXCEEDED(30003, "AI服务配额不足"),
    STORAGE_SERVICE_ERROR(30004, "存储服务异常"),
    KAFKA_ERROR(30005, "消息队列异常"),

    // ==================== 限流熔断 4xxxx ====================
    RATE_LIMIT_EXCEEDED(40001, "请求过于频繁，请稍后重试"),
    CIRCUIT_BREAKER_OPEN(40002, "服务熔断中，请稍后重试"),
    TOKEN_EXPIRED(40003, "登录已过期，请重新登录"),
    TOKEN_INVALID(40004, "无效的认证凭证");

    /**
     * 错误码 - 唯一标识
     */
    private final int code;

    /**
     * 错误信息 - 默认提示
     */
    private final String message;

    /**
     * 根据code获取枚举
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.getCode() == code) {
                return errorCode;
            }
        }
        return SYSTEM_ERROR;
    }
}
