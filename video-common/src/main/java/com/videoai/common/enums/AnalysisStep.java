package com.videoai.common.enums;

/** 展示与诊断用途，不是独立调度状态机。 */
public enum AnalysisStep {
    PREPARING_AUDIO, TRANSCRIBING, SCREENING, PREPARING_SEGMENTS, ANALYZING_SEGMENTS, SUMMARIZING
}
