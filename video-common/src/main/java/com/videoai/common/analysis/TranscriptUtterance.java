package com.videoai.common.analysis;

/** ASR 时间应先补齐音轨偏移，再创建此对象。 */
public record TranscriptUtterance(String id, long startMs, long endMs, String text, int channelId) {
    public TranscriptUtterance {
        ContractChecks.text(id, "句段 ID");
        ContractChecks.range(startMs, endMs);
        ContractChecks.text(text, "转写文字");
        if (channelId < 0) throw new IllegalArgumentException("声道不能为负数");
    }
}
