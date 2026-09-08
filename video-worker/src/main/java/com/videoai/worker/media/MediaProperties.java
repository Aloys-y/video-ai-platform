package com.videoai.worker.media;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "analysis.media")
public class MediaProperties {
    private String ffmpeg = "ffmpeg";
    private String ffprobe = "ffprobe";
    private String tempRoot = System.getProperty("java.io.tmpdir") + "/videoai-media";
    private long maxSourceBytes = 10L * 1024 * 1024 * 1024;
    private long maxWorkspaceBytes = 14L * 1024 * 1024 * 1024;
    private long minFreeBytes = 512L * 1024 * 1024;
    private int maxConcurrentWorkspaces = 2;
    private int processTimeoutSeconds = 600;
    private long maxVideoDurationMs = 12L * 60 * 60 * 1000;
    private long audioPartMs = 30L * 60 * 1000;
    private int maxAudioParts = 64;
    private int maxSegments = 8;
    private long maxSegmentMs = 180_000;
    private long maxSelectedMs = 1_800_000;
}
