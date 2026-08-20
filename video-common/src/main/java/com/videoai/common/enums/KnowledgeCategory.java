package com.videoai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum KnowledgeCategory {

    LEGEND("LEGEND"),
    WEAPON("WEAPON"),
    MAP("MAP"),
    TACTIC("TACTIC"),
    MECHANIC("MECHANIC"),
    PATCH("PATCH");

    private final String code;

    public static KnowledgeCategory fromCode(String code) {
        for (KnowledgeCategory category : values()) {
            if (category.code.equalsIgnoreCase(code)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown knowledge category: " + code);
    }
}
