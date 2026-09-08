package com.videoai.common.analysis;

/** 随不可变片段清单保存；重试沿用原参考，不混用更新后的知识。 */
public record SegmentGuidance(String userPrompt, String retrievalContext) {
    public SegmentGuidance {
        userPrompt = userPrompt == null ? "" : userPrompt.strip();
        retrievalContext = retrievalContext == null ? "" : retrievalContext;
        if (userPrompt.length() > 10000 || retrievalContext.length() > 12000)
            throw new IllegalArgumentException("片段分析参考超过预算");
    }
}
