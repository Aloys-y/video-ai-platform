package com.videoai.api.interceptor;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.api.context.UserContext;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.domain.User;
import com.videoai.common.enums.ErrorCode;
import com.videoai.infra.mysql.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API Key认证拦截器
 *
 * 面试重点：
 * 1. 为什么用拦截器而不是过滤器？
 *    - 拦截器是Spring组件，可注入Bean
 *    - 拦截器可以获取HandlerMethod，做更细粒度控制
 *    - 过滤器是Servlet层，更底层
 *
 * 2. API Key放在Header还是Query参数？
 *    - 推荐Header：X-API-Key
 *    - Query参数可能被日志记录，不安全
 *    - 但要支持两种方式，兼容性更好
 *
 * 3. 为什么不用Spring Security？
 *    - 场景简单，不需要完整的安全框架
 *    - 自己实现更轻量，面试更容易讲清楚
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * Header中的API Key名称
     */
    private static final String HEADER_API_KEY = "X-API-Key";

    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        // 1. 从Header或参数获取API Key
        String apiKey = extractApiKey(request);

        if (StrUtil.isBlank(apiKey)) {
            log.warn("Missing API Key, URI: {}", request.getRequestURI());
            writeErrorResponse(response, ErrorCode.PARAM_MISSING, "缺少API Key");
            return false;
        }

        // 2. 验证API Key格式
        if (!isValidApiKeyFormat(apiKey)) {
            log.warn("Invalid API Key format: {}", maskApiKey(apiKey));
            writeErrorResponse(response, ErrorCode.PARAM_ERROR, "API Key格式错误");
            return false;
        }

        // 3. 查询用户
        User user = userMapper.selectByApiKey(apiKey);
        if (user == null) {
            log.warn("Invalid API Key: {}", maskApiKey(apiKey));
            writeErrorResponse(response, ErrorCode.USER_FORBIDDEN, "无效的API Key");
            return false;
        }

        // 4. 检查用户状态
        if (!user.isEnabled()) {
            log.warn("User disabled: {}", user.getUserId());
            writeErrorResponse(response, ErrorCode.USER_FORBIDDEN, "用户已被禁用");
            return false;
        }

        // 5. 存入ThreadLocal
        UserContext.setUser(user);

        log.debug("Auth success, userId: {}, URI: {}", user.getUserId(), request.getRequestURI());

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 清理ThreadLocal，防止内存泄漏
        UserContext.clear();
    }

    /**
     * 从请求中提取API Key
     * 仅从Header获取，避免密钥出现在URL中
     */
    private String extractApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader(HEADER_API_KEY);
        return StrUtil.isNotBlank(apiKey) ? apiKey : null;
    }

    /**
     * 验证API Key格式
     * 格式：生产环境前缀 或 测试环境前缀 + 随机字符串
     */
    private boolean isValidApiKeyFormat(String apiKey) {
        if (apiKey == null || apiKey.length() < 10) {
            return false;
        }
        return apiKey.startsWith("sk_live_") || apiKey.startsWith("sk_test_");
    }

    /**
     * 脱敏API Key（日志中使用）
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 12) {
            return "***";
        }
        return apiKey.substring(0, 8) + "***";
    }

    /**
     * 写入错误响应
     */
    private void writeErrorResponse(HttpServletResponse response, ErrorCode errorCode,
                                    String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        ApiResponse<Void> apiResponse = ApiResponse.error(errorCode, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}
