package com.videoai.infra.redis.key;

/**
 * Redis Key定义
 *
 * 面试重点：
 * 1. 为什么要统一定义Key？
 *    - 避免Key冲突
 *    - 便于维护和查找
 *    - 统一前缀，方便监控和管理
 *
 * 2. Key的设计原则？
 *    - 业务前缀:模块:标识
 *    - 例：videoai:upload:session:{uploadId}
 *
 * 3. 为什么用常量类而不是枚举？
 *    - 需要动态拼接参数，枚举不方便
 *    - 常量方法更灵活
 */
public final class RedisKey {

    private RedisKey() {}

    /**
     * Key前缀
     */
    public static final String PREFIX = "videoai:";

    // ==================== 上传相关 ====================

    /**
     * 上传会话锁
     * 用途：防止并发上传同一分片
     * 过期时间：30秒
     */
    public static String uploadLock(String uploadId, Integer chunkIndex) {
        return PREFIX + "upload:lock:" + uploadId + ":" + chunkIndex;
    }

    /**
     * 上传进度缓存
     * 用途：快速查询上传进度，减少数据库查询
     * 过期时间：24小时
     */
    public static String uploadProgress(String uploadId) {
        return PREFIX + "upload:progress:" + uploadId;
    }

    // ==================== 任务相关 ====================

    /**
     * 任务详情缓存
     * 用途：查询任务状态时先查缓存
     * 过期时间：1小时
     */
    public static String taskDetail(String taskId) {
        return PREFIX + "task:detail:" + taskId;
    }

    /**
     * 用户任务列表缓存
     * 用途：用户查询任务列表
     * 过期时间：5分钟
     */
    public static String userTaskList(Long userId) {
        return PREFIX + "task:user:" + userId;
    }

    // ==================== 限流相关 ====================

    /**
     * 上传限流Key
     * 用途：限制用户上传频率
     * 算法：令牌桶/滑动窗口
     */
    public static String uploadRateLimit(Long userId) {
        return PREFIX + "limit:upload:" + userId;
    }

    /**
     * API限流Key
     * 用途：限制接口调用频率
     * 过期时间：1秒（滑动窗口）
     */
    public static String apiRateLimit(String apiName, Long userId) {
        return PREFIX + "limit:api:" + apiName + ":" + userId;
    }

    /**
     * 用户全局限流Key
     * 用途：限制用户所有接口的调用频率
     * 过期时间：1秒（滑动窗口）
     */
    public static String userRateLimit(Long userId) {
        return PREFIX + "limit:user:" + userId;
    }

    // ==================== 配额相关 ====================

    /**
     * 用户配额缓存
     * 用途：Redis原子扣减配额
     * 过期时间：同数据库配额周期
     */
    public static String userQuota(Long userId) {
        return PREFIX + "quota:user:" + userId;
    }

    /**
     * 配额分布式锁
     * 用途：配额扣减时加锁，防止超扣
     */
    public static String quotaLock(Long userId) {
        return PREFIX + "quota:lock:" + userId;
    }

    // ==================== 分布式锁 ====================

    /**
     * 任务处理锁
     * 用途：防止同一任务被多个Worker同时处理
     * 过期时间：10分钟（比最长处理时间稍长）
     */
    public static String taskProcessLock(String taskId) {
        return PREFIX + "lock:task:" + taskId;
    }

    // ==================== 统计相关 ====================

    /**
     * 实时统计Key
     * 用途：统计上传数、处理数等
     * 过期时间：按日统计，次日失效
     */
    public static String dailyStats(String statName) {
        java.time.LocalDate today = java.time.LocalDate.now();
        return PREFIX + "stats:" + statName + ":" + today;
    }
}
