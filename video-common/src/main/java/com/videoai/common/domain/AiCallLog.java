package com.videoai.common.domain;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiCallLog {
    private String callId;
    private String taskId;
    private int executionNo;
    private String stage;
    private int subtaskNo;
    private String model;
    private String requestId;
    private String remoteTaskId;
    private String status;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal audioSeconds;
    private String usageJson;
    private String priceSnapshot;
    private BigDecimal estimatedCostCny;
    private String costUnknownReason;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
