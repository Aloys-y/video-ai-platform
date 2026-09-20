package com.videoai.common.analysis;

/** 显式传入工作线程；与业务租约校验相互独立。 */
public record AiCallContext(String taskId, int executionNo, Stage stage, int subtaskNo) {
    public enum Stage { ASR, TEXT_SCREEN, VIDEO_ANALYSIS, RAG_EMBEDDING, RAG_RERANK }
    public AiCallContext {
        if (taskId == null || taskId.isBlank() || taskId.length() > 64
                || executionNo < 0 || stage == null || subtaskNo < 0)
            throw new IllegalArgumentException("无效的AI调用归属");
    }
}
