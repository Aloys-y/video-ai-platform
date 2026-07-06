package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum KnowledgeIndexStatus {

    DRAFT("DRAFT"),
    PENDING("PENDING"),
    INDEXING("INDEXING"),
    INDEXED("INDEXED"),
    FAILED("FAILED");

    private final String code;
}
