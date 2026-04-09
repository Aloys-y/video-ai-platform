package com.videoai.api.interceptor;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import com.videoai.api.context.UserContext;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.domain.User;
import com.videoai.common.enums.ErrorCode;
import com.videoai.infra.redis.key.RedisKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 限流拦截器 - 三级限流
 *
 * 面试重点：
 *
 * 1. 为什么需要三级限流？
 *    - 全局限流：保护系统整体，防止雪崩
 *    - 用户限流：防止单用户占用过多资源
 *    - 接口限流：保护核心接口，如上传接口
 *
 * 2. 为什么全局用Guava，用户用Redis？
 *    - 全局限流：单机内存，性能最高
 *    - 用户限流：分布式环境，需要共享计数
 *    - 如果是多实例部署，全局限流也该用Redis
 *
 * 3. 滑动窗口vs令牌桶vs漏桶？
 *    - 令牌桶：允许突发流量，适合API
 *    - 滑动窗口：精确控制QPS，适合严格限流
 *    - 漏桶：平滑流量，适合保护下游
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String HEADER_API_KEY = "X-API-Key";

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
        // 直接从请求提取 API Key，不依赖 UserContext（认证拦截器在限流之后执行）
        String apiKey = extractApiKey(request);
        if (StrUtil.isNotBlank(apiKey)) {
            if (!checkUserRateLimitByApiKey(apiKey)) {
                log.warn("User rate limit exceeded, apiKey: {}***, URI: {}",
                        apiKey.substring(0, Math.min(apiKey.length(), 8)), request.getRequestURI());
                writeRateLimitResponse(response, "请求过于频繁，请稍后重试");
                return false;
            }
        }

        return true;
    }

    /**
     * 从请求中提取 API Key
     */
    private String extractApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (StrUtil.isNotBlank(apiKey)) {
            return apiKey;
        }
        return null;
    }

    /**
     * 用户级别限流
     * 使用 API Key 的哈希值作为限流维度，避免在限流阶段查库
     */
    private boolean checkUserRateLimitByApiKey(String apiKey) {
        // 用 API Key 做哈希作为限流 key，避免查库
        String key = RedisKey.userRateLimit((long) apiKey.hashCode());
        RAtomicLong counter = redissonClient.getAtomicLong(key);

        long count = counter.incrementAndGet();

        // 第一次访问，设置过期时间
        if (count == 1) {
            counter.expire(USER_WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        // 限流阈值：认证通过后 UserContext 可用，此处用默认值
        // 实际用户级限流阈值在认证后的请求中可通过 UserContext 获取
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
