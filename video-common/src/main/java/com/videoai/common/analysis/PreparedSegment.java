package com.videoai.common.analysis;

public record PreparedSegment(int segmentNo, long startMs, long endMs, String objectKey) {
    public PreparedSegment {
        if (segmentNo < 0) throw new IllegalArgumentException("片段序号从 0 开始");
        ContractChecks.range(startMs, endMs);
        ContractChecks.objectKey(objectKey);
    }
}
