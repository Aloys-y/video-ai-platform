package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 上传进度响应
 */
@Data
@Builder
public class UploadProgressResponse {

    private String uploadId;

    /**
     * 总分片数
     */
    private Integer totalChunks;

    /**
     * 已上传的分片索引列表
     */
    private List<Integer> uploadedChunks;

    /**
     * 上传进度百分比（0-100）
     */
    private Integer progress;

    /**
     * 上传状态
     */
    private String status;
}
