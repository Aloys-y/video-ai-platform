package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outbox投递状态
 */
@Getter
@AllArgsConstructor
public enum TaskOutboxStatus {

    NEW("NEW"),
    SENDING("SENDING"),
    SENT("SENT"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED");

    private final String code;
}
