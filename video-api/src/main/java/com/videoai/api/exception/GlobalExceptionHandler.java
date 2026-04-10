package com.videoai.api.exception;

import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 捕获 Service 层抛出的异常，统一转换为 ApiResponse 格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business error: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        HttpStatus status = mapHttpStatus(e.getErrorCode());
        return ResponseEntity.status(status).body(ApiResponse.error(e.getErrorCode()));
    }

    /**
     * 参数校验异常（@Valid 触发）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String detail = fieldError != null
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : "参数校验失败";
        log.warn("Validation error: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.PARAM_ERROR, detail));
    }

    /**
     * 兜底异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SYSTEM_ERROR));
    }

    /**
     * 将 ErrorCode 映射为 HTTP 状态码
     */
    private HttpStatus mapHttpStatus(ErrorCode errorCode) {
        int code = errorCode.getCode();
        if (code >= 40000) return HttpStatus.TOO_MANY_REQUESTS;      // 4xxxx 限流熔断
        if (code >= 30000) return HttpStatus.SERVICE_UNAVAILABLE;      // 3xxxx 第三方服务
        if (code == 23005 || code == 40003 || code == 40004) return HttpStatus.UNAUTHORIZED; // 认证失败
        if (code == 23002) return HttpStatus.FORBIDDEN;                // 用户被禁止
        return HttpStatus.BAD_REQUEST;                                  // 其余业务错误
    }
}
