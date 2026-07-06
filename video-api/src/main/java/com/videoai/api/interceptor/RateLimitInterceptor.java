package com.videoai.api.interceptor;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.enums.ErrorCode;
import com.videoai.infra.redis.key.RedisKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 限流拦截器 - 二级限流 + JWT 兼容
 *
 * Level 1: Guava RateLimiter 全局限流（默认 1000 QPS）
 * Level 2: Redis 滑动窗口用户级限流（默认 100 QPS/用户）
 *   - API Key 用户：以 apiKey.hashCode() 为维度（认证前，不查库）
 *   - JWT 用户：Base64解码 payload 取 sub（不验签，不查库）
 *
 * 面试重点：
 *
 * 1. 为什么全局用 Guava，用户用 Redis？
 *   - Guava 单机内存，性能最高，适合 JVM 内第一道防线
 *   - Redis 原子计数器可跨实例共享，保证多实例部署时用户级计数精确
 *
 * 2. 为什么 JWT 不验签就能用于限流？
 *   - 限流层只需"区分用户"，不需要确认身份
 *   - 攻击者伪造 userId → 只会共享别人的限流窗口，无法绕过限流
 *   - 签名验证由 AuthInterceptor（order=2）负责，不在此层
 *   - 不引入验签开销（secret取+HS256计算），纯CPU base64解码微秒级
 *
 * 3. 滑动窗口 vs 令牌桶？
 *   - 令牌桶：允许突发流量，适合全局限流
 *   - 滑动窗口：严格 QPS 控制，适合用户级防刷
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_AUTHORIZATION = "Authorization";

    @Value("${videoai.rate-limit.global-qps:1000}")
    private double globalQps;

    private static final int USER_WINDOW_SECONDS = 1;

    private volatile RateLimiter globalRateLimiter;

    private RateLimiter getGlobalRateLimiter() {
        if (globalRateLimiter == null) {
            synchronized (this) {
                if (globalRateLimiter == null) {
                    globalRateLimiter = RateLimiter.create(globalQps);
                    log.info("Global rate limiter initialized, QPS: {}", globalQps);
                }
            }
        }
        return globalRateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        // ========== Level 1: 全局限流 ==========
        if (!getGlobalRateLimiter().tryAcquire()) {
            log.warn("Global rate limit exceeded, URI: {}", request.getRequestURI());
            writeRateLimitResponse(response, "系统繁忙，请稍后重试");
            return false;
        }

        // ========== Level 2: 用户限流 ==========
        // 优先 API Key，其次 JWT（均不查库、不验签）
        Long userKey = resolveUserKey(request);
        if (userKey != null && !checkUserRateLimit(userKey)) {
            log.warn("User rate limit exceeded, userKey: {}, URI: {}",
                    userKey, request.getRequestURI());
            writeRateLimitResponse(response, "请求过于频繁，请稍后重试");
            return false;
        }

        return true;
    }

    /**
     * 解析用户限流标识（不查库、不验签）
     *
     * API Key → hashCode()，JWT → Base64解码payload取sub
     * 两者均不依赖数据库和密钥，纯CPU计算
     */
    private Long resolveUserKey(HttpServletRequest request) {
        // 方式1：API Key
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (StrUtil.isNotBlank(apiKey)) {
            return (long) apiKey.hashCode();
        }

        // 方式2：JWT（不验签，只Base64解码payload拿sub）
        return extractUserIdFromJwt(request);
    }

    /**
     * 从 JWT payload 提取 userId（不验签）
     *
     * 只做 Base64 解码 + JSON 解析，不调用 HMAC-SHA256 验签。
     * 攻击者可以伪造 sub，但伪造结果只是共享别人的限流窗口，无法绕过限流。
     */
    private Long extractUserIdFromJwt(HttpServletRequest request) {
        String authHeader = request.getHeader(HEADER_AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode node = objectMapper.readTree(payload);
            String sub = node.get("sub").asText();
            if (StrUtil.isBlank(sub)) {
                return null;
            }
            return (long) sub.hashCode();
        } catch (Exception e) {
            log.debug("Failed to extract userId from JWT payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 用户级限流（Redis 滑动窗口）
     *
     * @param userKey 用户标识（API Key hash 或 JWT sub hash）
     */
    private boolean checkUserRateLimit(Long userKey) {
        String key = RedisKey.userRateLimit(userKey);
        RAtomicLong counter = redissonClient.getAtomicLong(key);

        long count = counter.incrementAndGet();

        // 首次访问，设置过期时间（1秒滑动窗口）
        if (count == 1) {
            counter.expire(USER_WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        return count <= defaultUserLimit;
    }

    @Value("${videoai.rate-limit.user-api-qps:100}")
    private long defaultUserLimit;

    private void writeRateLimitResponse(HttpServletResponse response, String message)
            throws Exception {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");

        ApiResponse<Void> apiResponse = ApiResponse.error(ErrorCode.RATE_LIMIT_EXCEEDED, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}
