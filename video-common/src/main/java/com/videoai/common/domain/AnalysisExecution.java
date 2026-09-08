package com.videoai.common.domain;

import lombok.Data;
import java.time.LocalDateTime;

/** 每代一行。配置创建后不可改，中间产物只允许由空值写入一次。 */
@Data
public class AnalysisExecution {
    private Long id;
    private String taskId;
    private Integer executionNo;
    private String analysisMode;
    /** 包括 ASR/文本/视频模型版本、提示词、时间扩展及媒体参数；不得包含密钥。 */
    private String configSnapshot;
    private String configHash;
    /** 原始视频内容摘要，不使用临时签名 URL 作为身份。 */
    private String inputHash;
    private String asrTaskId;
    private String transcriptObjectKey;
    private String candidatesObjectKey;
    private String segmentsObjectKey;
    private LocalDateTime createdAt;
}
