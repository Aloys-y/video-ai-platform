package com.videoai.common.utils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * ID生成器
 *
 * 面试重点：
 * 1. 为什么不直接用UUID？
 *    - UUID太长（36字符），存储和索引效率低
 *    - UUID是无序的，对B+树索引不友好
 *
 * 2. 为什么不直接用数据库自增ID？
 *    - 暴露业务量
 *    - 分库分表时不方便
 *
 * 3. 这个ID生成方案有什么特点？
 *    - 趋势递增：对数据库索引友好
 *    - 包含时间戳：可以根据ID判断创建时间
 *    - 包含随机数：防止碰撞和预测
 */
public class IdGenerator {

    /**
     * 起始时间戳（2024-01-01 00:00:00）
     * 用于计算相对时间，让ID更短
     */
    private static final long EPOCH = 1704038400000L;

    /**
     * 生成上传会话ID
     * 格式：upload_{timestamp}_{random}
     * 例：upload_1704038400_abc123
     */
    public static String generateUploadId() {
        long timestamp = (System.currentTimeMillis() - EPOCH) / 1000;
        String random = randomString(6);
        return String.format("upload_%d_%s", timestamp, random);
    }

    /**
     * 生成任务ID
     * 格式：task_{timestamp}_{random}
     */
    public static String generateTaskId() {
        long timestamp = (System.currentTimeMillis() - EPOCH) / 1000;
        String random = randomString(6);
        return String.format("task_%d_%s", timestamp, random);
    }

    /**
     * 生成分片存储路径
     * 格式：chunks/{uploadId}/chunk_{index}
     */
    public static String generateChunkPath(String uploadId, int chunkIndex) {
        return String.format("chunks/%s/chunk_%d", uploadId, chunkIndex);
    }

    /**
     * 生成视频存储路径
     * 格式：videos/{year}/{month}/{day}/{uploadId}.mp4
     * 面试点：为什么按日期分目录？
     * 1. 便于按时间清理过期文件
     * 2. 分散存储，避免单目录文件过多
     * 3. 便于统计每日上传量
     */
    public static String generateVideoPath(String uploadId, String extension) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return String.format("videos/%04d/%02d/%02d/%s.%s",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                uploadId, extension);
    }

    /**
     * 生成指定长度的随机字符串（小写字母+数字）
     */
    private static String randomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
