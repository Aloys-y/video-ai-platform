package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 初始化上传响应
 */
@Data
@Builder
public class UploadInitResponse {

    /**
     * 上传会话ID
     */
    private String uploadId;

    /**
     * 分片大小（字节）
     */
    private Integer chunkSize;

    /**
     * 总分片数
     */
    private Integer totalChunks;

    /**
     * 已上传的分片索引列表（断点续传用）
     */
    private List<Integer> uploadedChunks;

    /**
     * 是否秒传命中
     */
    private Boolean instantUpload;

    /**
     * 秒传命中时的任务ID
     */
    private String taskId;
}
