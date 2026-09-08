package com.videoai.worker.service;

import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 任务失败落库服务。
 *
 * 不自动重发同一片段的付费请求。失败后不创建新的 Outbox，等待用户手动重新分析。
 */
@Service
@RequiredArgsConstructor
public class TaskFailureService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final com.videoai.infra.mysql.mapper.AnalysisSegmentMapper segments;

    /**
     * 仅当任务仍处于当前执行代次的 PROCESSING 状态时标记失败。
     *
     * @return true 表示成功标记失败；false 表示该执行已经过期或被其他流程处理
     */
    @org.springframework.transaction.annotation.Transactional(timeout = 10)
    public boolean markExecutionFailed(String taskId, int executionNo, String errorMessage) {
        boolean changed = analysisTaskMapper.markFailed(taskId, executionNo, errorMessage) == 1;
        if (changed) segments.stopUnfinished(taskId, executionNo);
        return changed;
    }
}
