package com.videoai.common.analysis;

import com.videoai.common.enums.SegmentStatus;

/** 仅表示单片段终态，不表示整局任务完成。usageJson 为本次调用实际用量。 */
public record SegmentAnalysisResult(int segmentNo, SegmentStatus status, String result,
                                    String errorMessage, String usageJson) {
    public SegmentAnalysisResult {
        if (segmentNo < 0) throw new IllegalArgumentException("片段序号从 0 开始");
        if (status == SegmentStatus.SUCCEEDED) {
            ContractChecks.text(result, "成功结果");
            if (errorMessage != null) throw new IllegalArgumentException("成功结果不能带错误");
        } else if (status == SegmentStatus.FAILED) {
            ContractChecks.text(errorMessage, "失败原因");
            if (result != null) throw new IllegalArgumentException("失败结果不能作为成功内容");
        } else {
            throw new IllegalArgumentException("片段结果必须是终态");
        }
    }
}
