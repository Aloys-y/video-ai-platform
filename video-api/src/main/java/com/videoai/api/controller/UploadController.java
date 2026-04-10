package com.videoai.api.controller;

import com.videoai.api.context.UserContext;
import com.videoai.api.service.UploadService;
import com.videoai.common.domain.User;
import com.videoai.common.dto.request.InitUploadRequest;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.dto.response.UploadInitResponse;
import com.videoai.common.dto.response.UploadProgressResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传接口
 */
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /**
     * 初始化上传
     */
    @PostMapping("/init")
    public ApiResponse<UploadInitResponse> init(@Valid @RequestBody InitUploadRequest request) {
        User user = UserContext.getUser();
        UploadInitResponse response = uploadService.init(user.getId(), request);
        return ApiResponse.success(response);
    }

    /**
     * 上传分片
     */
    @PostMapping("/chunk")
    public ApiResponse<UploadProgressResponse> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-Upload-Id") String uploadId,
            @RequestHeader("X-Chunk-Index") Integer chunkIndex) {
        UploadProgressResponse response = uploadService.uploadChunk(uploadId, chunkIndex, file);
        return ApiResponse.success(response);
    }

    /**
     * 完成上传（合并分片）
     */
    @PostMapping("/complete")
    public ApiResponse<String> complete(@RequestHeader("X-Upload-Id") String uploadId) {
        String taskId = uploadService.completeUpload(uploadId);
        return ApiResponse.success(taskId);
    }

    /**
     * 查询上传状态
     */
    @GetMapping("/status/{uploadId}")
    public ApiResponse<UploadProgressResponse> status(@PathVariable("uploadId") String uploadId) {
        UploadProgressResponse response = uploadService.getStatus(uploadId);
        return ApiResponse.success(response);
    }
}
