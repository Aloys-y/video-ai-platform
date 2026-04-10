package com.videoai.common.dto.response;

import com.videoai.common.enums.ErrorCode;
import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

/**
 * 统一API响应包装类
 *
 * 面试重点：
 * 1. 为什么要统一响应格式？
 *    - 前后端对接标准化
 *    - 便于拦截器统一处理
 *    - 便于监控系统统计成功/失败率
 *
 * 2. 为什么包含traceId？
 *    - 分布式链路追踪
 *    - 用户反馈问题时，提供traceId快速定位
 *
 * 3. success字段和code字段是否冗余？
 *    - 不冗余：success表示请求是否成功，code表示具体业务状态
 *    - 成功时code=0，失败时code=具体错误码
 */
@Data
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误码
     * 0: 成功
     * 其他: 具体错误码
     */
    private Integer code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 业务数据
     */
    private T data;

    /**
     * 请求追踪ID
     * 用于日志关联和问题排查
     */
    private String traceId;

    /**
     * 服务器时间戳
     */
    private Long timestamp;

    // ==================== 静态工厂方法 ====================

    /**
     * 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(true);
        response.setCode(ErrorCode.SUCCESS.getCode());
        response.setMessage(ErrorCode.SUCCESS.getMessage());
        response.setData(data);
        response.setTimestamp(System.currentTimeMillis());
        return response;
    }

    /**
     * 成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * 生成简短 traceId（取 UUID 前8位）
     */
    private static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 失败响应（使用错误码）
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setCode(errorCode.getCode());
        response.setMessage(errorCode.getMessage());
        response.setTraceId(generateTraceId());
        response.setTimestamp(System.currentTimeMillis());
        return response;
    }

    /**
     * 失败响应（使用错误码+自定义消息）
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setCode(errorCode.getCode());
        response.setMessage(message);
        response.setTraceId(generateTraceId());
        response.setTimestamp(System.currentTimeMillis());
        return response;
    }

    /**
     * 失败响应（使用错误码+参数）
     * 例：error(ErrorCode.PARAM_ERROR, "文件名不能为空")
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String... args) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setCode(errorCode.getCode());
        // 简单拼接错误信息
        response.setMessage(errorCode.getMessage() + (args.length > 0 ? ": " + String.join(", ", args) : ""));
        response.setTraceId(generateTraceId());
        response.setTimestamp(System.currentTimeMillis());
        return response;
    }
}
