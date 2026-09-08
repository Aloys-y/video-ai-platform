package com.videoai.api.config;

import com.videoai.common.enums.AnalysisMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.*;

/** 创建时固定分析模式；关开关不改变已有任务及其重试模式。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "videoai.analysis")
public class AnalysisModePolicy {
    private boolean prefilterEnabled;
    private Set<Long> prefilterUserIds = new HashSet<>();
    public String forUser(Long userId) {
        return prefilterEnabled && prefilterUserIds.contains(userId) ? AnalysisMode.AUDIO_PREFILTER.name() : AnalysisMode.DIRECT_VIDEO.name();
    }
}
