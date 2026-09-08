package com.videoai.worker.segment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "analysis.segments")
public class SegmentAnalysisProperties {
    private int threads = 4;
    // 容量是待压测起点：4 并发、单段约 30 秒、目标排队约 120 秒，估算为 16。
    private int queueCapacity = 16;
    private long requestIntervalMs = 1000;
    private long cancellationGraceMs = 2000;
    private int maxResponseBytes = 1024 * 1024;
    public void validate() {
        if (threads < 1 || threads > 64 || queueCapacity < 1 || queueCapacity > 1024
                || requestIntervalMs < 1 || requestIntervalMs > 60000
                || cancellationGraceMs < 0 || cancellationGraceMs > 10000
                || maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024)
            throw new IllegalArgumentException("片段并发、队列、期限或响应预算配置无效");
    }
}
