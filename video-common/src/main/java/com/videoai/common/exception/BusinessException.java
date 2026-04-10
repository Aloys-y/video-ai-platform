package com.videoai.common.exception;

import com.videoai.common.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常
 * 用于 Service 层抛出业务错误，由全局异常处理器统一捕获转换为 ApiResponse
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
