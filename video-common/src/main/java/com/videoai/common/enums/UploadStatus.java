package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 上传会话状态枚举
 */
@Getter
@AllArgsConstructor
public enum UploadStatus {

    /**
     * 上传中 - 分片正在上传
     */
    UPLOADING(0, "上传中"),

    /**
     * 已完成 - 所有分片上传完成，等待合并
     */
    COMPLETED(1, "已完成"),

    /**
     * 已合并 - 分片已合并为完整文件
     */
    MERGED(2, "已合并"),

    /**
     * 已过期 - 上传超时，会话失效
     * 面试点：为什么需要过期状态？
     * 防止用户上传一半就离开，占用存储空间
     * 定时任务清理过期的分片文件
     */
    EXPIRED(3, "已过期"),

    /**
     * 合并失败 - 分片合并出错
     */
    MERGE_FAILED(4, "合并失败");

    private final int code;
    private final String description;

    public static UploadStatus fromCode(int code) {
        for (UploadStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid UploadStatus code: " + code);
    }
}
