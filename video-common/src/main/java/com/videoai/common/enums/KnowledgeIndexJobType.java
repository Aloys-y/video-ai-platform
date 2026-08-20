package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum KnowledgeIndexJobType {

    UPSERT_CARD("UPSERT_CARD"),
    REBUILD_ALL("REBUILD_ALL");

    private final String code;
}
