package com.videoai.api.context;

import com.videoai.common.domain.User;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户上下文 - ThreadLocal存储当前请求的用户信息
 *
 * 面试重点：
 * 1. 为什么用ThreadLocal？
 *    - 每个请求独立线程，ThreadLocal隔离
 *    - 避免用户信息在方法间层层传递
 *    - 任意位置可获取当前用户
 *
 * 2. 内存泄漏问题？
 *    - 必须在请求结束时clear()
 *    - 使用try-finally或在拦截器afterCompletion中清理
 *
 * 3. 线程池场景？
 *    - Tomcat线程池会复用线程
 *    - 如果不清理，下一个请求会读到上一个用户信息
 *    - 这是严重的安全漏洞！
 */
@Slf4j
public final class UserContext {

    private UserContext() {}

    /**
     * ThreadLocal存储用户信息
     * 使用InheritableThreadLocal支持异步线程（可选）
     */
    private static final ThreadLocal<User> USER_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前用户
     */
    public static void setUser(User user) {
        USER_HOLDER.set(user);
        log.debug("UserContext set user: {}", user != null ? user.getUserId() : null);
    }

    /**
     * 获取当前用户
     */
    public static User getUser() {
        return USER_HOLDER.get();
    }

    /**
     * 获取当前用户ID
     */
    public static Long getUserId() {
        User user = getUser();
        return user != null ? user.getId() : null;
    }

    /**
     * 获取当前用户业务ID
     */
    public static String getUserBizId() {
        User user = getUser();
        return user != null ? user.getUserId() : null;
    }

    /**
     * 判断当前用户是否为管理员
     */
    public static boolean isAdmin() {
        User user = getUser();
        return user != null && user.isAdmin();
    }

    /**
     * 判断当前用户是否为VIP
     */
    public static boolean isVip() {
        User user = getUser();
        return user != null && user.isVip();
    }

    /**
     * 清除上下文（必须调用！）
     * 在拦截器afterCompletion中调用
     */
    public static void clear() {
        USER_HOLDER.remove();
        log.debug("UserContext cleared");
    }
}
