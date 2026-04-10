package com.videoai.api.service;

import cn.hutool.core.io.FileUtil;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.domain.UploadSession;
import com.videoai.common.dto.request.InitUploadRequest;
import com.videoai.common.dto.response.UploadInitResponse;
import com.videoai.common.dto.response.UploadProgressResponse;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.enums.UploadStatus;
import com.videoai.common.exception.BusinessException;
import com.videoai.common.utils.IdGenerator;
import com.videoai.infra.kafka.topic.TopicConstant;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.mysql.mapper.UploadSessionMapper;
import com.videoai.infra.redis.key.RedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 上传服务
 * 核心流程：初始化 → 分片上传 → 合并完成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final UploadSessionMapper uploadSessionMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${videoai.upload.chunk-size:5242880}")
    private long defaultChunkSize;

    @Value("${videoai.upload.session-expire-hours:24}")
    private int sessionExpireHours;

    @Value("${videoai.upload.allowed-types:mp4,avi,mov,mkv,wmv,flv}")
    private String allowedTypes;

    @Value("${videoai.upload.max-file-size:5368709120}")
    private long maxFileSize;

    @PostConstruct
    public void init() {
        storageService.ensureBucketExists();
        log.info("UploadService initialized, bucket ensured");
    }

    /**
     * 初始化上传
     */
    public UploadInitResponse init(Long userId, InitUploadRequest request) {
        // 1. 校验文件类型
        String extension = FileUtil.extName(request.getFileName());
        if (extension == null || !isAllowedType(extension)) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_TYPE_NOT_SUPPORT);
        }

        // 2. 校验文件大小
        if (request.getFileSize() > maxFileSize) {
            throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE);
        }

        // 3. 秒传检查
        if (request.getFileHash() != null) {
            UploadSession existing = uploadSessionMapper.selectByFileHash(request.getFileHash());
            if (existing != null && existing.getStatusEnum() == UploadStatus.MERGED) {
                log.info("Instant upload hit, fileHash={}, existingUploadId={}", request.getFileHash(), existing.getUploadId());
                // 创建新任务关联已有文件
                AnalysisTask task = createAnalysisTask(userId, existing.getUploadId(), existing.getStoragePath());
                return UploadInitResponse.builder()
                        .uploadId(existing.getUploadId())
                        .instantUpload(true)
                        .taskId(task.getTaskId())
                        .build();
            }
        }

        // 4. 创建上传会话
        String uploadId = IdGenerator.generateUploadId();
        int chunkSize = request.getChunkSize() != null ? request.getChunkSize().intValue() : (int) defaultChunkSize;
        int totalChunks = (int) Math.ceil((double) request.getFileSize() / chunkSize);

        UploadSession session = new UploadSession();
        session.setUploadId(uploadId);
        session.setUserId(userId);
        session.setFileName(request.getFileName());
        session.setFileHash(request.getFileHash());
        session.setTotalSize(request.getFileSize());
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setUploadedChunks("[]");
        session.setStatus(UploadStatus.UPLOADING.getCode());
        session.setExpiredAt(LocalDateTime.now().plusHours(sessionExpireHours));

        uploadSessionMapper.insert(session);

        log.info("Upload session created, uploadId={}, totalChunks={}, chunkSize={}", uploadId, totalChunks, chunkSize);

        return UploadInitResponse.builder()
                .uploadId(uploadId)
                .chunkSize(chunkSize)
                .totalChunks(totalChunks)
                .uploadedChunks(List.of())
                .instantUpload(false)
                .build();
    }

    /**
     * 上传分片
     */
    public UploadProgressResponse uploadChunk(String uploadId, Integer chunkIndex, MultipartFile file) {
        // 1. 查询上传会话
        UploadSession session = getValidSession(uploadId);

        // 2. 校验分片索引
        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new BusinessException(ErrorCode.UPLOAD_CHUNK_INDEX_ERROR);
        }

        // 3. 检查分片是否已上传
        if (session.isChunkUploaded(chunkIndex)) {
            return buildProgressResponse(session);
        }

        // 4. Redisson 分布式锁，防并发上传同一分片
        String lockKey = RedisKey.uploadLock(uploadId, chunkIndex);
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 等待最多5秒获取锁，锁自动过期时间30秒，watchdog自动续期
            boolean locked = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("Chunk upload lock failed, uploadId={}, chunkIndex={}", uploadId, chunkIndex);
                throw new BusinessException(ErrorCode.UPLOAD_CHUNK_UPLOADING);
            }

            // 双重检查：获取锁后再确认分片未上传
            session = uploadSessionMapper.selectByUploadId(uploadId);
            if (session.isChunkUploaded(chunkIndex)) {
                return buildProgressResponse(session);
            }

            // 5. 上传到 MinIO
            String chunkPath = IdGenerator.generateChunkPath(uploadId, chunkIndex);
            storageService.putObject(chunkPath, file.getInputStream(), file.getSize(), file.getContentType());

            // 6. MySQL 原子追加已传分片索引
            uploadSessionMapper.appendUploadedChunk(uploadId, chunkIndex);

            // 7. 更新 Redis 进度缓存
            refreshProgressCache(uploadId);

            log.info("Chunk uploaded, uploadId={}, chunkIndex={}", uploadId, chunkIndex);
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取锁被中断");
        } catch (Exception e) {
            log.error("Chunk upload failed, uploadId={}, chunkIndex={}", uploadId, chunkIndex, e);
            throw new BusinessException(ErrorCode.UPLOAD_CHUNK_UPLOAD_FAILED);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        // 重新查询以获取最新进度
        session = uploadSessionMapper.selectByUploadId(uploadId);
        return buildProgressResponse(session);
    }

    /**
     * 完成上传（合并分片）
     */
    @Transactional
    public String completeUpload(String uploadId) {
        // 1. 查询并校验
        UploadSession session = getValidSession(uploadId);

        if (session.getStatusEnum() != UploadStatus.UPLOADING) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND, "上传会话状态不正确");
        }

        // 2. 检查所有分片是否已上传
        if (!session.isAllChunksUploaded()) {
            throw new BusinessException(ErrorCode.UPLOAD_CHUNK_SIZE_ERROR,
                    String.format("还有 %d 个分片未上传", session.getTotalChunks() - session.getUploadedChunkIndexList().size()));
        }

        // 3. 更新状态为 COMPLETED
        uploadSessionMapper.updateStatus(uploadId, UploadStatus.COMPLETED.getCode());

        try {
            // 4. MinIO 合并分片
            String extension = FileUtil.extName(session.getFileName());
            String videoPath = IdGenerator.generateVideoPath(uploadId, extension);

            List<String> chunkPaths = new ArrayList<>();
            for (int i = 0; i < session.getTotalChunks(); i++) {
                chunkPaths.add(IdGenerator.generateChunkPath(uploadId, i));
            }
            storageService.composeObject(videoPath, chunkPaths);

            // 5. 清理分片文件
            storageService.removeObjects(chunkPaths);

            // 6. 更新存储路径和状态
            uploadSessionMapper.setStoragePath(uploadId, videoPath);

            // 7. 创建分析任务
            AnalysisTask task = createAnalysisTask(session.getUserId(), uploadId, videoPath);

            log.info("Upload completed and merged, uploadId={}, videoPath={}, taskId={}",
                    uploadId, videoPath, task.getTaskId());

            return task.getTaskId();
        } catch (Exception e) {
            uploadSessionMapper.updateStatus(uploadId, UploadStatus.MERGE_FAILED.getCode());
            log.error("Merge failed, uploadId={}", uploadId, e);
            throw new BusinessException(ErrorCode.UPLOAD_MERGE_FAILED);
        }
    }

    /**
     * 查询上传状态
     */
    public UploadProgressResponse getStatus(String uploadId) {
        UploadSession session = uploadSessionMapper.selectByUploadId(uploadId);
        if (session == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return buildProgressResponse(session);
    }

    // ==================== 私有方法 ====================

    private UploadSession getValidSession(String uploadId) {
        UploadSession session = uploadSessionMapper.selectByUploadId(uploadId);
        if (session == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (session.isExpired()) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED);
        }
        return session;
    }

    private boolean isAllowedType(String extension) {
        return allowedTypes.contains(extension.toLowerCase());
    }

    private AnalysisTask createAnalysisTask(Long userId, String uploadId, String storagePath) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setUploadId(uploadId);
        task.setUserId(userId);
        task.setVideoUrl(storagePath);
        task.setStatusEnum(TaskStatus.PENDING);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setMaxRetry(3);

        analysisTaskMapper.insert(task);

        // 异步发送 Kafka 消息通知 Worker，不阻塞主流程
        try {
            kafkaTemplate.send(TopicConstant.TASK_TOPIC, task.getTaskId(), buildTaskMessage(task));
        } catch (Exception e) {
            log.warn("Kafka send failed, taskId={}. Task will be picked up later.", task.getTaskId(), e);
        }

        log.info("Analysis task created, taskId={}, uploadId={}", task.getTaskId(), uploadId);
        return task;
    }

    private java.util.Map<String, Object> buildTaskMessage(AnalysisTask task) {
        return java.util.Map.of(
                "taskId", task.getTaskId(),
                "uploadId", task.getUploadId(),
                "userId", task.getUserId(),
                "videoUrl", task.getVideoUrl(),
                "status", task.getStatus()
        );
    }

    private void refreshProgressCache(String uploadId) {
        String key = RedisKey.uploadProgress(uploadId);
        UploadSession session = uploadSessionMapper.selectByUploadId(uploadId);
        if (session != null) {
            String value = session.getProgress() + "%";
            redisTemplate.opsForValue().set(key, value, 24, TimeUnit.HOURS);
        }
    }

    private UploadProgressResponse buildProgressResponse(UploadSession session) {
        return UploadProgressResponse.builder()
                .uploadId(session.getUploadId())
                .totalChunks(session.getTotalChunks())
                .uploadedChunks(session.getUploadedChunkIndexList())
                .progress(session.getProgress())
                .status(session.getStatusEnum() != null ? session.getStatusEnum().name() : "UNKNOWN")
                .build();
    }
}
