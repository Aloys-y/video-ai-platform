package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outbox事件类型
 */
@Getter
@AllArgsConstructor
public enum OutboxEventType {

    TASK_EXECUTE("TASK_EXECUTE");

    private final String code;
}
