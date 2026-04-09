package com.videoai.api.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoai.common.domain.User;
import com.videoai.common.enums.UserRole;
import com.videoai.infra.mysql.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户服务
 *
 * 面试重点：
 * 1. API Key生成策略
 *    - 前缀区分生产环境和测试环境
 *    - 随机部分：32字节随机数，Base64编码
 *    - 唯一性：数据库唯一索引保证
 *
 * 2. API Secret加密存储
 *    - 使用BCrypt单向加密
 *    - 即使数据库泄露也无法还原
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 创建用户
     * 自动生成API Key和Secret
     */
    @Transactional
    public User createUser(String username, String email, UserRole role) {
        // 检查用户名是否已存在
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }

        // 生成API Key
        String apiKey = generateApiKey(true); // 生产环境
        String apiSecret = generateApiSecret();

        // 创建用户
        User user = new User();
        user.setUserId(generateUserId());
        user.setUsername(username);
        user.setEmail(email);
        user.setApiKey(apiKey);
        user.setApiSecret(hashSecret(apiSecret)); // 加密存储
        user.setRole(role.getCode());
        user.setStatus(1);
        user.setRateLimit(role.getDefaultRateLimit());

        userMapper.insert(user);

        log.info("User created, userId: {}, apiKey: {}", user.getUserId(), maskApiKey(apiKey));

        // Secret 只通过返回值传递给调用方，不记录到日志
        // 调用方负责将 secret 展示给用户并提示保存
        user.setApiSecret(apiSecret); // 返回明文 secret，仅此一次

        return user;
    }

    /**
     * 根据API Key查询用户
     */
    public User getUserByApiKey(String apiKey) {
        return userMapper.selectByApiKey(apiKey);
    }

    /**
     * 根据用户ID查询
     */
    public User getUserByUserId(String userId) {
        return userMapper.selectByUserId(userId);
    }

    /**
     * 生成API Key
     * 格式：<prefix>_xxxxxxxxxxxxxxxxxxxxxxxxxx
     *
     * 面试点：为什么这样设计？
     * - 前缀：行业惯例，表示Secret Key + 环境标识
     * - live/test：区分环境，便于日志过滤
     * - 随机部分：足够长，防碰撞
     */
    private String generateApiKey(boolean isProduction) {
        String prefix = isProduction ? "sk_live_" : "sk_test_";
        byte[] randomBytes = new byte[24];
        RANDOM.nextBytes(randomBytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return prefix + randomPart;
    }

    /**
     * 生成API Secret
     * 用于签名验证（预留功能）
     */
    private String generateApiSecret() {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * 生成用户ID
     * 格式：usr_xxxxxxxxxxxxxxxx
     */
    private String generateUserId() {
        return "usr_" + IdUtil.fastSimpleUUID().substring(0, 16);
    }

    /**
     * 加密Secret
     * 使用BCrypt单向加密
     *
     * 面试点：为什么用BCrypt？
     * - 自带盐值，无需额外管理
     * - 可配置计算强度（cost factor）
     * - 抗彩虹表和暴力破解攻击
     */
    private String hashSecret(String secret) {
        return BCrypt.hashpw(secret, BCrypt.gensalt(12));
    }

    /**
     * 脱敏API Key
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 12) {
            return "***";
        }
        return apiKey.substring(0, 8) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
