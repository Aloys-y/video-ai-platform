package com.videoai.worker.service;

import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 任务失败落库服务。
 *
 * AI 每个执行代次只调用一次。失败后不创建新的 Outbox，等待用户手动重新分析。
 */
@Service
@RequiredArgsConstructor
public class TaskFailureService {

    private final AnalysisTaskMapper analysisTaskMapper;

    /**
     * 仅当任务仍处于当前执行代次的 PROCESSING 状态时标记失败。
     *
     * @return true 表示成功标记失败；false 表示该执行已经过期或被其他流程处理
     */
    public boolean markExecutionFailed(String taskId, int executionNo, String errorMessage) {
        return analysisTaskMapper.markFailed(taskId, executionNo, errorMessage) == 1;
    }
}
