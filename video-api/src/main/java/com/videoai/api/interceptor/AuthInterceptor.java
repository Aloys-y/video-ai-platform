package com.videoai.api.interceptor;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.api.context.UserContext;
import com.videoai.api.util.JwtUtil;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.domain.User;
import com.videoai.common.enums.ErrorCode;
import com.videoai.infra.mysql.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 双模式认证拦截器
 *
 * 支持两种认证方式：
 * 1. JWT Token: Authorization: Bearer <token>（Web端登录后使用）
 * 2. API Key: X-API-Key: sk_live_xxx（API调用使用）
 *
 * 优先级：JWT > API Key
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        // 放行 CORS 预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 1. 尝试 JWT Token 认证
        String authHeader = request.getHeader(HEADER_AUTHORIZATION);
        if (StrUtil.isNotBlank(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            return handleJwtAuth(authHeader.substring(BEARER_PREFIX.length()), response);
        }

        // 2. 尝试 API Key 认证
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (StrUtil.isNotBlank(apiKey)) {
            return handleApiKeyAuth(apiKey, response);
        }

        // 3. 两者都无
        log.warn("Missing auth credentials, URI: {}", request.getRequestURI());
        writeErrorResponse(response, ErrorCode.PARAM_MISSING, "缺少认证凭证");
        return false;
    }

    /**
     * JWT Token 认证
     */
    private boolean handleJwtAuth(String token, HttpServletResponse response) throws Exception {
        try {
            Claims claims = jwtUtil.parseToken(token);
            String userId = claims.getSubject();

            User user = userMapper.selectByUserId(userId);
            if (user == null) {
                writeErrorResponse(response, ErrorCode.USER_FORBIDDEN, "用户不存在");
                return false;
            }

            if (!user.isEnabled()) {
                writeErrorResponse(response, ErrorCode.USER_FORBIDDEN, "用户已被禁用");
                return false;
            }

            UserContext.setUser(user);
            log.debug("JWT auth success, userId: {}", user.getUserId());
            return true;
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            writeErrorResponse(response, ErrorCode.TOKEN_EXPIRED, "登录已过期，请重新登录");
            return false;
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            writeErrorResponse(response, ErrorCode.TOKEN_INVALID, "无效的认证凭证");
            return false;
        }
    }

    /**
     * API Key 认证
     */
    private boolean handleApiKeyAuth(String apiKey, HttpServletResponse response) throws Exception {
        if (!isValidApiKeyFormat(apiKey)) {
            writeErrorResponse(response, ErrorCode.PARAM_ERROR, "API Key格式错误");
            return false;
        }

        User user = userMapper.selectByApiKey(apiKey);
        if (user == null) {
            writeErrorResponse(response, ErrorCode.USER_FORBIDDEN, "无效的API Key");
            return false;
        }

        if (!user.isEnabled()) {
            writeErrorResponse(response, ErrorCode.USER_FORBIDDEN, "用户已被禁用");
            return false;
        }

        UserContext.setUser(user);
        log.debug("API Key auth success, userId: {}", user.getUserId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean isValidApiKeyFormat(String apiKey) {
        if (apiKey == null || apiKey.length() < 10) {
            return false;
        }
        return apiKey.startsWith("sk_live_") || apiKey.startsWith("sk_test_");
    }

    private void writeErrorResponse(HttpServletResponse response, ErrorCode errorCode,
                                    String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        ApiResponse<Void> apiResponse = ApiResponse.error(errorCode, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}
