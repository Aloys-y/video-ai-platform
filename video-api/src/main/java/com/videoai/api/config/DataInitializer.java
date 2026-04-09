package com.videoai.api.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoai.common.domain.User;
import com.videoai.common.enums.UserRole;
import com.videoai.infra.mysql.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据初始化配置
 *
 * 面试重点：
 * 1. CommandLineRunner是什么？
 *    - Spring Boot启动后执行的回调
 *    - 常用于初始化数据、预热缓存
 *
 * 2. 为什么用@Profile("dev")？
 *    - 只在开发环境初始化
 *    - 生产环境不需要
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final UserMapper userMapper;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 初始化测试数据
     * 仅在开发环境启用
     */
    @Bean
    @Profile("dev") // 只在dev环境执行
    public CommandLineRunner initData() {
        return args -> {
            log.info("Start initializing test data...");

            // 检查测试用户是否存在
            Long count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getUsername, "testuser")
            );

            if (count > 0) {
                log.info("Test user already exists, skip initialization");
                return;
            }

            // 创建测试用户
            User user = new User();
            user.setUserId("usr_" + generateRandomString(12));
            user.setUsername("testuser");
            user.setEmail("test@videoai.com");

            String apiKey = "sk_live_Dev" + generateRandomString(24);
            String apiSecret = generateRandomString(32);
            String testPassword = "test123";
            user.setApiKey(apiKey);
            user.setApiSecret(hashSecret(apiSecret));
            user.setPassword(BCrypt.hashpw(testPassword, BCrypt.gensalt(12)));
            user.setRole("USER");
            user.setStatus(1); // 1=正常
            user.setRateLimit(100); // 默认100 QPS

            userMapper.insert(user);

            log.info("========================================");
            log.info("Test user created!");
            log.info("User ID: {}", user.getUserId());
            log.info("Username: {}", user.getUsername());
            log.info("========================================");

            // 密钥仅输出到控制台（不经过日志框架），避免被日志系统持久化
            System.out.println("========================================");
            System.out.println("API Key:    " + apiKey);
            System.out.println("API Secret: " + apiSecret);
            System.out.println("Email:      test@videoai.com");
            System.out.println("Password:   " + testPassword);
            System.out.println("Usage (API Key): curl -H 'X-API-Key: " + apiKey + "' http://localhost:8080/api/test/auth");
            System.out.println("Usage (Login):   curl -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{\"email\":\"test@videoai.com\",\"password\":\"test123\"}'");
            System.out.println("========================================");
        };
    }

    private String generateRandomString(int length) {
        byte[] randomBytes = new byte[length];
        RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes).substring(0, length);
    }

    private String hashSecret(String secret) {
        return BCrypt.hashpw(secret, BCrypt.gensalt(12));
    }
}
