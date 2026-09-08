package com.videoai.common.domain;

import lombok.Data;

/** 音轨分段的提交/结果记录，不用于任务领取。 */
@Data
public class AnalysisAsrPart {
    private String taskId;
    private Integer executionNo;
    private Integer partNo;
    private Long startMs;
    private Long endMs;
    private String audioObjectKey;
    /** NULL=未提交；SUBMITTING=提交结果未确定，禁止自动重提；其余为远端ID。 */
    private String asrTaskId;
    private String transcriptObjectKey;
    private String usageJson;
    /** 复用来源代次，片段序号不变；用于追溯历史费用。 */
    private Integer reusedExecutionNo;
}
