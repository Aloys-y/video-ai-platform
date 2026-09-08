package com.videoai.worker.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.infra.minio.config.MinioConfig;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.worker.asr.*;
import com.videoai.worker.config.DashScopeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用的真实服务冒烟：只查询 P0 已有 ASR，不新增付费转写。凭据只从环境读取。 */
@EnabledIfEnvironmentVariable(named = "P2_LIVE_SMOKE", matches = "true")
class P2LiveSmokeTest {
    @TempDir Path root;

    @Test void existingAsrAndRealVideoStorageRoundTrip() throws Exception {
        var json = new ObjectMapper(); var asrConfig = new AsrProperties();
        asrConfig.setApiKey(System.getenv("ASR_API_KEY"));
        var client = new DashScopeAsrClient(asrConfig, new DashScopeConfig(), json);
        var query = client.query(System.getenv("P2_ASR_TASK_ID"));
        assertEquals("SUCCEEDED", query.status());
        var transcript = client.downloadResult(query.resultUrl(), 0, 0, Long.parseLong(System.getenv("P2_ASR_DURATION_MS")));
        assertFalse(transcript.utterances().isEmpty());
        var config = new MinioConfig(); config.setEndpoint(System.getenv("P2_S3_ENDPOINT"));
        config.setAccessKey(System.getenv("P2_S3_ACCESS_KEY")); config.setSecretKey(System.getenv("P2_S3_SECRET_KEY"));
        config.setBucketName(System.getenv("P2_S3_BUCKET")); config.setRegion(System.getenv("P2_S3_REGION"));
        var props = new MediaProperties(); props.setTempRoot(root.toString()); props.setMinFreeBytes(0);
        props.setFfmpeg(System.getenv("FFMPEG_PATH")); props.setFfprobe(System.getenv("FFPROBE_PATH"));
        var media = new MediaPreparationService(props, json);
        long bytes;
        try (var s3 = config.s3Client(); var signer = config.s3Presigner(); var ws = media.open()) {
            var storage = new StorageService(s3, signer, config);
            Path original = Path.of(System.getenv("P2_SOURCE_VIDEO"));
            var info = media.probe(ws, original);
            Path clip = media.clip(ws, original, info, 0, 50000, 53000);
            bytes = Files.size(clip);
            String key = storage.putArtifact(clip, "video/mp4");
            try {
                Path downloaded = ws.file("roundtrip.mp4");
                storage.downloadToFile(key, downloaded, 10 * 1024 * 1024, Duration.ofSeconds(60), 0);
                assertEquals(AudioPrefilterPreparationService.sha256(clip), AudioPrefilterPreparationService.sha256(downloaded));
                assertThrows(java.io.IOException.class, () -> storage.downloadToFile(key, ws.file("too-large.mp4"), 1, Duration.ofSeconds(60), 0));
            } finally { storage.removeObject(key); }
        }
        Path repo = Files.isDirectory(Path.of("sql")) ? Path.of(".") : Path.of("..");
        Files.createDirectories(repo.resolve("logs"));
        json.writeValue(repo.resolve("logs/p2-live-smoke.json").toFile(), Map.of("existingAsrQuery", "SUCCEEDED",
                "utteranceCount", transcript.utterances().size(), "videoClipBytes", bytes,
                "storageHashMatched", true, "oversizedDownloadRejected", true, "newAsrSubmissions", 0));
    }
}
