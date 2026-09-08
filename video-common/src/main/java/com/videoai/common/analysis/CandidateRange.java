package com.videoai.common.analysis;

import java.util.List;
import java.util.Objects;

/** 类型和理由来自粗筛；时间由程序根据句段映射，不接受模型自造时间。 */
public record CandidateRange(long startMs, long endMs, List<String> utteranceIds,
                             Type type, String reason) {
    public enum Type { CONTACT, ENGAGEMENT, RECOVERY }

    public CandidateRange {
        ContractChecks.range(startMs, endMs);
        utteranceIds = List.copyOf(Objects.requireNonNull(utteranceIds));
        if (utteranceIds.isEmpty()) throw new IllegalArgumentException("候选必须有来源句段");
        utteranceIds.forEach(id -> ContractChecks.text(id, "句段 ID"));
        Objects.requireNonNull(type, "候选类型");
        ContractChecks.text(reason, "候选理由");
    }
}
