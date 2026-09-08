package com.videoai.common.domain;

import lombok.Data;

/** 调用留痕：有行无响应=状态不明；有响应则复用并重新解析，不重复收费。 */
@Data
public class AnalysisTextCall {
    private String taskId;
    private Integer executionNo;
    private String purpose;
    private Integer batchNo;
    private String requestHash;
    private String responseObjectKey;
}
