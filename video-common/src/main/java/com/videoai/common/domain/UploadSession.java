package com.videoai.common.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.videoai.common.enums.UploadStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上传会话实体
 *
 * 面试重点：
 * 1. 为什么用upload_id而不是用主键id作为唯一标识？
 *    - 主键id可能暴露业务量
 *    - upload_id是业务无关的，更安全
 *
 * 2. 为什么uploaded_chunks用JSON存储？
 *    - 分片数量有限，不会太大
 *    - 避免多表关联
 *    - MySQL 5.7+支持JSON类型和函数
 *
 * 3. 为什么需要file_hash字段？
 *    - 实现秒传：相同hash的文件直接复用
 */
@Data
@TableName("upload_session")
public class UploadSession {

    /**
     * 主键ID（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 上传会话ID（业务主键）
     * 格式：upload_{timestamp}_{random}
     * 面试点：为什么用这种格式？
     * 1. 可读性好，一眼就知道是upload相关
     * 2. 时间戳保证趋势递增，对索引友好
     * 3. 随机数防止碰撞
     */
    private String uploadId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件整体MD5哈希
     * 用于秒传和完整性校验
     */
    private String fileHash;

    /**
     * 文件总大小（字节）
     */
    private Long totalSize;

    /**
     * 分片大小（字节）
     * 面试点：为什么每个会话记录分片大小？
     * 不同网络环境可能使用不同分片大小
     * 客户端可以根据网络状况动态调整
     */
    private Integer chunkSize;

    /**
     * 总分片数
     */
    private Integer totalChunks;

    /**
     * 已上传的分片索引列表
     * JSON数组格式：[0, 1, 2, 5, 6]
     * 面试点：为什么存索引而不是分片哈希？
     * 索引更紧凑，占用空间小
     * 分片上传时已经校验了MD5
     */
    private String uploadedChunks;

    /**
     * 上传状态
     * 使用枚举的code值存储
     */
    private Integer status;

    /**
     * 合并后的存储路径
     * 格式：videos/{year}/{month}/{day}/{uploadId}.mp4
     */
    private String storagePath;

    /**
     * 创建时间
     * 面试点：为什么用LocalDateTime而不是Date？
     * 1. LocalDateTime是Java 8新API，线程安全
     * 2. 不依赖时区，更清晰
     * 3. API更友好
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 过期时间
     * 上传会话有效期为24小时
     */
    private LocalDateTime expiredAt;

    // ==================== 业务方法 ====================

    /**
     * 获取已上传分片索引列表
     */
    public List<Integer> getUploadedChunkIndexList() {
        if (uploadedChunks == null || uploadedChunks.isEmpty()) {
            return List.of();
        }
        // 解析JSON数组
        return cn.hutool.json.JSONUtil.toList(uploadedChunks, Integer.class);
    }

    /**
     * 设置已上传分片索引列表
     */
    public void setUploadedChunkIndexList(List<Integer> chunks) {
        this.uploadedChunks = cn.hutool.json.JSONUtil.toJsonStr(chunks);
    }

    /**
     * 添加已上传分片
     */
    public void addUploadedChunk(Integer chunkIndex) {
        List<Integer> chunks = getUploadedChunkIndexList();
        if (!chunks.contains(chunkIndex)) {
            chunks.add(chunkIndex);
            setUploadedChunkIndexList(chunks);
        }
    }

    /**
     * 检查分片是否已上传
     */
    public boolean isChunkUploaded(Integer chunkIndex) {
        return getUploadedChunkIndexList().contains(chunkIndex);
    }

    /**
     * 检查是否所有分片已上传完成
     */
    public boolean isAllChunksUploaded() {
        List<Integer> uploaded = getUploadedChunkIndexList();
        return uploaded.size() == totalChunks;
    }

    /**
     * 获取上传进度百分比
     */
    public int getProgress() {
        if (totalChunks == null || totalChunks == 0) {
            return 0;
        }
        return (int) ((getUploadedChunkIndexList().size() * 100.0) / totalChunks);
    }

    /**
     * 获取状态枚举
     */
    public UploadStatus getStatusEnum() {
        return status != null ? UploadStatus.fromCode(status) : null;
    }

    /**
     * 检查会话是否已过期
     */
    public boolean isExpired() {
        return expiredAt != null && LocalDateTime.now().isAfter(expiredAt);
    }
}
