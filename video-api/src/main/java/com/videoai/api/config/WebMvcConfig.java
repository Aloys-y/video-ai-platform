package com.videoai.api.config;

import com.videoai.api.interceptor.AuthInterceptor;
import com.videoai.api.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC配置
 *
 * 面试重点：
 * 1. 拦截器顺序：先限流，后认证
 *    - 限流在前，防止恶意请求打到数据库
 *    - 认证在后，正常请求才查库
 *
 * 2. 排除路径：某些接口不需要认证
 *    - 健康检查
 *    - Swagger文档
 *    - 公开API
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Value("${videoai.cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private List<String> allowedOrigins;

    /**
     * 注册拦截器
     * 注意：执行顺序按addInterceptor顺序
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 全局限流（最先执行）
            registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .order(1);

        // 2. 认证拦截器
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/error",
                        "/druid/**",
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        // 认证接口（公开）
                        "/auth/register",
                        "/auth/login",
                        // 测试接口（仅 dev 环境）
                        "/test/user/**",
                        // 公开接口
                        "/api/public/**"
                )
                .order(2);
    }

    /**
     * 跨域配置
     * 通过配置文件指定允许的来源，不使用通配符
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
