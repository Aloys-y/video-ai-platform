package com.videoai.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.SegmentReview;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.exception.BusinessException;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import com.videoai.infra.mysql.mapper.AnalysisSegmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 按当前用户和执行代次读取；不向客户端暴露 OSS 对象键、原始响应或用量记录。 */
@Service
@RequiredArgsConstructor
public class TaskSegmentService {
    private final AnalysisTaskMapper tasks;
    private final AnalysisSegmentMapper segments;
    private final StorageService storage;
    private final ObjectMapper json;

    public record Item(int segmentNo, long startMs, long endMs, String status,
                       SegmentReview review, String errorMessage, boolean reused) {}
    public record Result(int executionNo, String taskStatus, String currentStep, int total, long succeeded, List<Item> segments) {}
    public record Playback(String url, long originalStartMs) {}

    public AnalysisTask ownedTask(String taskId, Long userId) {
        if (userId == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        var task = tasks.selectOne(new LambdaQueryWrapper<AnalysisTask>()
                .eq(AnalysisTask::getTaskId, taskId).eq(AnalysisTask::getUserId, userId));
        if (task == null || !Objects.equals(userId, task.getUserId())) throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        return task;
    }

    public Result list(String taskId, Long userId) {
        var task = ownedTask(taskId, userId);
        int no = task.getRetryCount() == null ? 0 : task.getRetryCount();
        var items = new ArrayList<Item>();
        for (var row : segments.selectExecution(taskId, no)) {
            SegmentReview review = null; String error = row.getErrorMessage();
            if ("SUCCEEDED".equals(row.getStatus())) {
                try {
                    review = json.readValue(row.getResult(), SegmentReview.class);
                    if (review.segmentNo() != row.getSegmentNo() || review.startMs() != row.getStartMs() || review.endMs() != row.getEndMs())
                        throw new IllegalArgumentException();
                } catch (Exception e) { review = null; error = "片段结果暂不可读取，请重试或联系管理员"; }
            }
            items.add(new Item(row.getSegmentNo(), row.getStartMs(), row.getEndMs(), row.getStatus(), review, error,
                    row.getReusedExecutionNo() != null));
        }
        items.sort(Comparator.comparingLong(Item::startMs));
        return new Result(no, task.getStatus(), task.getCurrentStep(), items.size(),
                items.stream().filter(i -> "SUCCEEDED".equals(i.status())).count(), List.copyOf(items));
    }

    public Playback playback(String taskId, int segmentNo, int executionNo, Long userId) {
        var task = ownedTask(taskId, userId);
        int no = task.getRetryCount() == null ? 0 : task.getRetryCount();
        if (no != executionNo) throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        var row = segments.selectExecution(taskId, no).stream().filter(s -> s.getSegmentNo() == segmentNo)
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        return new Playback(storage.getPresignedUrl(row.getObjectKey(), 1), row.getStartMs());
    }
}
