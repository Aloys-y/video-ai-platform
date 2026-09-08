package com.videoai.infra.minio.service;

import com.videoai.infra.minio.config.MinioConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对象存储服务，封装 S3 操作（兼容 MinIO / Backblaze B2 / AWS S3）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final MinioConfig minioConfig;

    /** 媒体流水线专用：限流式读写，避免原片进入 Java 堆或签名 URL 出现在异常中。 */
    public void downloadToFile(String key, java.nio.file.Path target, long maxBytes,
                               java.time.Duration timeout, long minFreeBytes) throws java.io.IOException {
        long deadline = System.nanoTime() + com.videoai.common.analysis.ExecutionBudget.limit(timeout).toNanos();
        for (int attempt = 1; ; attempt++) {
            com.videoai.common.analysis.ExecutionBudget.check();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.io.IOException("对象下载总期限已耗尽");
            try {
                downloadAttempt(key, target, maxBytes, Duration.ofNanos(remaining), minFreeBytes, deadline);
                return;
            } catch (DownloadReadException e) {
                com.videoai.common.analysis.ExecutionBudget.check();
                if (attempt >= 3 || System.nanoTime() >= deadline)
                    throw new java.io.IOException("原始对象下载中断，有限重试未成功，请稍后重试", e);
                log.warn("对象下载流中断，准备第 {} 次下载", attempt + 1);
                try { Thread.sleep(Math.min(attempt * 1000L, Math.max(1, (deadline - System.nanoTime()) / 1_000_000))); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("下载重试已中断");
                }
            }
        }
    }

    /** 仅重试响应体读取中断；本地磁盘、权限、取消及预算错误不重试。 */
    private static final class DownloadReadException extends java.io.IOException {
        DownloadReadException(java.io.IOException cause) { super("对象响应体读取中断", cause); }
    }

    private void downloadAttempt(String key, java.nio.file.Path target, long maxBytes, Duration timeout,
                                 long minFreeBytes, long deadline) throws java.io.IOException {
        boolean created = false, completed = false;
        try (var input = s3Client.getObject(GetObjectRequest.builder().bucket(minioConfig.getBucketName()).key(key)
                .overrideConfiguration(c -> c.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout)).build())) {
            try {
                Long length = input.response().contentLength();
                if (length != null && length > maxBytes) throw new java.io.IOException("对象超过下载大小限制");
                try (var output = java.nio.file.Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    created = true;
                    byte[] buffer = new byte[64 * 1024]; long total = 0;
                    while (true) {
                        com.videoai.common.analysis.ExecutionBudget.check();
                        if (System.nanoTime() >= deadline) throw new java.io.IOException("下载总期限已耗尽");
                        int n;
                        try { n = input.read(buffer); }
                        catch (java.io.IOException e) { throw new DownloadReadException(e); }
                        if (n == -1) break;
                        total += n;
                        if (total > maxBytes || java.nio.file.Files.getFileStore(target).getUsableSpace() < minFreeBytes)
                            throw new java.io.IOException("下载大小或磁盘预算不足");
                        output.write(buffer, 0, n);
                    }
                    if (length != null && total != length)
                        throw new DownloadReadException(new java.io.EOFException("对象长度不完整"));
                }
                completed = true;
            } finally { if (!completed) input.abort(); }
        } catch (java.io.IOException e) { throw e; }
        catch (Exception e) { throw new java.io.IOException("对象存储下载失败", e); }
        finally { if (created && !completed) java.nio.file.Files.deleteIfExists(target); }
    }

    /** 每次写入使用新 UUID 对象键，绑定数据库后不再覆盖该对象。 */
    public String putArtifact(java.nio.file.Path source, String contentType) throws java.io.IOException {
        var timeout = com.videoai.common.analysis.ExecutionBudget.limit(Duration.ofMinutes(10));
        String key = "analysis-artifacts/" + java.util.UUID.randomUUID();
        try {
            s3Client.putObject(PutObjectRequest.builder().bucket(minioConfig.getBucketName()).key(key)
                    .contentType(contentType).overrideConfiguration(c -> c.apiCallTimeout(timeout)
                            .apiCallAttemptTimeout(timeout)).build(), RequestBody.fromFile(source));
            return key;
        } catch (Exception e) { throw new java.io.IOException("中间产物上传失败"); }
    }

    /**
     * 确保桶存在，不存在则创建
     */
    public void ensureBucketExists() {
        String bucket = minioConfig.getBucketName();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (Exception e) {
            // B2 返回 404 S3Exception 而非 NoSuchBucketException，统一按状态码处理
            if (e instanceof NoSuchBucketException || e.getMessage().contains("404")) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created bucket: {}", bucket);
            } else {
                log.error("Failed to ensure bucket exists: {}", bucket, e);
                throw new RuntimeException("Bucket check failed", e);
            }
        }
    }

    /**
     * 上传对象（分片）
     */
    public void putObject(String objectName, InputStream stream, long size, String contentType) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectName)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build(),
                    RequestBody.fromInputStream(stream, size));
            log.debug("Uploaded object: {}", objectName);
        } catch (Exception e) {
            log.error("Failed to upload object: {}", objectName, e);
            throw new RuntimeException("Object upload failed", e);
        }
    }

    /**
     * 删除对象
     */
    public void removeObject(String objectName) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectName)
                            .build());
            log.debug("Removed object: {}", objectName);
        } catch (Exception e) {
            log.warn("Failed to remove object: {}", objectName, e);
        }
    }

    /**
     * 批量删除对象（分片清理）
     */
    public void removeObjects(List<String> objectNames) {
        for (String name : objectNames) {
            removeObject(name);
        }
    }

    /**
     * 生成预签名URL（临时访问链接）
     *
     * @param objectName 对象路径
     * @param expireHours 过期时间（小时）
     */
    public String getPresignedUrl(String objectName, int expireHours) {
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(expireHours))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectName)
                            .build())
                    .build();
            String url = s3Presigner.presignGetObject(presignRequest).url().toString();
            log.debug("Generated presigned URL for: {}, expires in {}h", objectName, expireHours);
            return url;
        } catch (Exception e) {
            log.error("Failed to generate presigned URL: {}", objectName, e);
            throw new RuntimeException("Presigned URL generation failed", e);
        }
    }

    // ==================== Multipart Upload API ====================

    /**
     * 创建 Multipart Upload
     * @param objectKey 最终对象路径
     * @return S3 Multipart Upload ID
     */
    public String createMultipartUpload(String objectKey) {
        try {
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
                    CreateMultipartUploadRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectKey)
                            .build());
            log.info("Created multipart upload: object={}, uploadId={}", objectKey, response.uploadId());
            return response.uploadId();
        } catch (Exception e) {
            log.error("Failed to create multipart upload: {}", objectKey, e);
            throw new RuntimeException("CreateMultipartUpload failed", e);
        }
    }

    /**
     * 上传一个 Part（对应一个 chunk）
     * @param objectKey 对象路径
     * @param uploadId  Multipart Upload ID
     * @param partNumber Part 编号（从 1 开始）
     * @param stream     Part 数据流
     * @param size       Part 大小（字节）
     * @return Part 的 ETag
     */
    public String uploadPart(String objectKey, String uploadId, int partNumber,
                             InputStream stream, long size) {
        try {
            UploadPartResponse response = s3Client.uploadPart(
                    UploadPartRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectKey)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .build(),
                    RequestBody.fromInputStream(stream, size));
            log.debug("Uploaded part: object={}, part={}, etag={}",
                    objectKey, partNumber, response.eTag());
            return response.eTag();
        } catch (Exception e) {
            log.error("Failed to upload part: object={}, partNumber={}", objectKey, partNumber, e);
            throw new RuntimeException("UploadPart failed", e);
        }
    }

    /**
     * 完成 Multipart Upload（合并所有 Parts）
     * @param objectKey 对象路径
     * @param uploadId  Multipart Upload ID
     * @param expectedPartCount 期望的 Part 数量（用于校验）
     */
    public void completeMultipartUpload(String objectKey, String uploadId, int expectedPartCount) {
        try {
            List<Part> parts = listParts(objectKey, uploadId);

            if (parts.size() < expectedPartCount) {
                throw new RuntimeException(
                        String.format("Parts count mismatch: got %d, expected %d", parts.size(), expectedPartCount));
            }

            List<CompletedPart> completedParts = parts.stream()
                    .map(p -> CompletedPart.builder()
                            .partNumber(p.partNumber())
                            .eTag(p.eTag())
                            .build())
                    .collect(Collectors.toList());

            s3Client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectKey)
                            .uploadId(uploadId)
                            .multipartUpload(CompletedMultipartUpload.builder()
                                    .parts(completedParts)
                                    .build())
                            .build());
            log.info("Completed multipart upload: object={}, parts={}", objectKey, parts.size());
        } catch (Exception e) {
            log.error("Failed to complete multipart upload: {}", objectKey, e);
            // 清理 B2 上的未完成上传，避免占用存储
            abortMultipartUpload(objectKey, uploadId);
            throw new RuntimeException("CompleteMultipartUpload failed", e);
        }
    }

    /**
     * 取消 Multipart Upload（清理已上传 Parts）
     * @param objectKey 对象路径
     * @param uploadId  Multipart Upload ID
     */
    public void abortMultipartUpload(String objectKey, String uploadId) {
        try {
            s3Client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectKey)
                            .uploadId(uploadId)
                            .build());
            log.info("Aborted multipart upload: object={}, uploadId={}", objectKey, uploadId);
        } catch (Exception e) {
            log.warn("Failed to abort multipart upload: object={}, uploadId={}", objectKey, uploadId, e);
        }
    }

    /**
     * 查询已上传的 Parts（按 partNumber 排序）
     * @param objectKey 对象路径
     * @param uploadId  Multipart Upload ID
     * @return 已上传的 Parts 列表
     */
    public List<Part> listParts(String objectKey, String uploadId) {
        try {
            ListPartsResponse response = s3Client.listParts(
                    ListPartsRequest.builder()
                            .bucket(minioConfig.getBucketName())
                            .key(objectKey)
                            .uploadId(uploadId)
                            .maxParts(10000)
                            .build());
            List<Part> parts = new ArrayList<>(response.parts());
            parts.sort(Comparator.comparingInt(Part::partNumber));
            log.debug("Listed parts: object={}, count={}", objectKey, parts.size());
            return parts;
        } catch (Exception e) {
            log.error("Failed to list parts: object={}, uploadId={}", objectKey, uploadId, e);
            throw new RuntimeException("ListParts failed", e);
        }
    }
}
