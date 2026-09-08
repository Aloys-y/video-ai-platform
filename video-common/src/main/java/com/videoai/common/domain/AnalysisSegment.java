package com.videoai.common.domain;

import lombok.Data;
import java.time.LocalDateTime;

/** 片段执行结果；时间统一为原视频毫秒坐标，objectKey 不含临时签名。 */
@Data
public class AnalysisSegment {
    private Long id;
    private String taskId;
    private Integer executionNo;
    private Integer segmentNo;
    private Long startMs;
    private Long endMs;
    private String objectKey;
    private String status;
    private String result;
    private String errorMessage;
    /** 厂商报告的实际用量 JSON，未知为 null，不以零代替。 */
    private String usageJson;
    /** 同任务此前成功执行的片段来源；复用时本次不应重复计费。 */
    private Integer reusedExecutionNo;
    private Integer reusedSegmentNo;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
