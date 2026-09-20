package com.videoai.common.domain;

import java.util.Set;

/** 非核心通知事件，不携带邮箱、报告或视频地址。 */
public record AnalysisFinishedEvent(String eventId, String taskId, int executionNo, String status) {
    public static AnalysisFinishedEvent of(String taskId, int executionNo, String status) {
        return new AnalysisFinishedEvent(taskId + ":" + executionNo + ":finished:v1", taskId, executionNo, status);
    }
    public void validate() {
        if (taskId == null || taskId.isBlank() || taskId.length() > 64 || executionNo < 0
                || !Set.of("SUCCEEDED", "PARTIAL", "FAILED").contains(status == null ? "" : status)
                || !of(taskId, executionNo, status).eventId().equals(eventId))
            throw new IllegalArgumentException("无效的分析结束事件");
    }
}
