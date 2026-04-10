package com.videoai.infra.minio.service;

import com.videoai.infra.minio.config.MinioConfig;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储服务，封装 MinIO 操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    /**
     * 确保桶存在，不存在则创建
     */
    public void ensureBucketExists() {
        String bucket = minioConfig.getBucketName();
        try {
            boolean found = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to ensure bucket exists: {}", bucket, e);
            throw new RuntimeException("MinIO bucket check failed", e);
        }
    }

    /**
     * 上传对象（分片）
     */
    public void putObject(String objectName, InputStream stream, long size, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectName)
                            .stream(stream, size, -1)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build());
            log.debug("Uploaded object: {}", objectName);
        } catch (Exception e) {
            log.error("Failed to upload object: {}", objectName, e);
            throw new RuntimeException("Object upload failed", e);
        }
    }

    /**
     * 合并分片为一个完整对象
     * 使用 MinIO Compose Object API，服务端合并，不下载到本地
     */
    public void composeObject(String destination, List<String> sources) {
        try {
            List<ComposeSource> composeSources = new ArrayList<>();
            for (String source : sources) {
                composeSources.add(
                        ComposeSource.builder()
                                .bucket(minioConfig.getBucketName())
                                .object(source)
                                .build());
            }
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(destination)
                            .sources(composeSources)
                            .build());
            log.info("Composed {} chunks into {}", sources.size(), destination);
        } catch (Exception e) {
            log.error("Failed to compose object: {}", destination, e);
            throw new RuntimeException("Object compose failed", e);
        }
    }

    /**
     * 删除对象
     */
    public void removeObject(String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectName)
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
}
