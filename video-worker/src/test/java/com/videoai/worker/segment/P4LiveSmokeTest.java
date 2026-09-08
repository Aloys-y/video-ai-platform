package com.videoai.worker.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.PreparedSegment;
import com.videoai.infra.minio.config.MinioConfig;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.worker.config.DashScopeConfig;
import com.videoai.worker.media.*;
import com.videoai.worker.screening.SegmentReviewParser;
import com.videoai.worker.service.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 明确启用的真实片段冒烟；提交前落本地记录，未知请求不自动重发，不写业务数据库。 */
@EnabledIfEnvironmentVariable(named = "P4_LIVE_SMOKE", matches = "true")
class P4LiveSmokeTest {
    @TempDir Path root;
    @Test void realClipsRunThroughSharedPoolAndStructuredProvider() throws Exception {
        var json = new ObjectMapper();
        Path repo = Files.isDirectory(Path.of("sql")) ? Path.of(".") : Path.of("..");
        Path logs = repo.resolve("logs"); Files.createDirectories(logs);
        var model = new DashScopeConfig(); model.setApiKey(System.getenv("ASR_API_KEY"));
        var provider = new DashScopeVideoProvider(model);
        var modelSettings = new SegmentModelSettings(provider);
        var props = new MediaProperties(); props.setTempRoot(root.toString()); props.setMinFreeBytes(0);
        props.setFfmpeg(System.getenv("FFMPEG_PATH")); props.setFfprobe(System.getenv("FFPROBE_PATH"));
        var media = new MediaPreparationService(props, json);
        var config = new MinioConfig(); config.setEndpoint(System.getenv("P2_S3_ENDPOINT"));
        config.setAccessKey(System.getenv("P2_S3_ACCESS_KEY")); config.setSecretKey(System.getenv("P2_S3_SECRET_KEY"));
        config.setBucketName(System.getenv("P2_S3_BUCKET")); config.setRegion(System.getenv("P2_S3_REGION"));
        var poolConfig = new SegmentAnalysisProperties(); var calls = new AtomicInteger();
        List<Map<String, Object>> timings = Collections.synchronizedList(new ArrayList<>());
        try (var s3 = config.s3Client(); var signer = config.s3Presigner(); var ws = media.open();
             var pool = new SegmentAnalysisExecutor(poolConfig)) {
            var storage = new StorageService(s3, signer, config);
            Path source = Path.of(System.getenv("P2_SOURCE_VIDEO")); var info = media.probe(ws, source);
            List<SegmentAnalysisExecutor.Work<PreparedSegment>> work = new ArrayList<>();
            for (int n = 0; n < 2; n++) {
                int no = n; long start = 50000 + n * 10000;
                var rawFile = logs.resolve("p4-live-segment-" + n + ".response.json");
                var requestFile = logs.resolve("p4-live-segment-" + n + ".request.json");
                Path clip = media.clip(ws, source, info, no, start, start + 5000);
                String clipHash;
                try (var in = Files.newInputStream(clip)) {
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[65536]; int read;
                    while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
                    clipHash = HexFormat.of().formatHex(digest.digest());
                }
                var request = json.valueToTree(Map.of("clipHash", clipHash, "startMs", start, "endMs", start + 5000,
                        "settings", modelSettings.snapshot()));
                if (Files.exists(requestFile) && !json.readTree(requestFile.toFile()).equals(json.readTree(json.writeValueAsBytes(request))))
                    throw new IllegalStateException("真实片段样本或配置变化，禁止覆盖原请求记录");
                if (Files.exists(requestFile) && !Files.exists(rawFile)) throw new IllegalStateException("真实片段调用结果未知，不自动重发");
                work.add(scope -> {
                    var segment = new PreparedSegment(no, start, start + 5000, "live-clip-" + no);
                    AiVideoProvider.DetailedResult response;
                    if (Files.exists(rawFile)) response = json.readValue(rawFile.toFile(), AiVideoProvider.DetailedResult.class);
                    else {
                        String key = storage.putArtifact(clip, "video/mp4");
                        try {
                            pool.awaitRequestPermit(scope);
                            Files.write(requestFile, json.writeValueAsBytes(request), StandardOpenOption.CREATE_NEW);
                            long begin = System.currentTimeMillis(); calls.incrementAndGet();
                            response = provider.callDetailed(storage.getPresignedUrl(key, 2), SegmentReviewParser.VIDEO_PROMPT);
                            Files.write(rawFile, json.writeValueAsBytes(response), StandardOpenOption.CREATE_NEW);
                            timings.add(Map.of("segmentNo", no, "startEpochMs", begin, "endEpochMs", System.currentTimeMillis()));
                        } finally { storage.removeObject(key); }
                    }
                    assertEquals("stop", response.finishReason());
                    var review = new SegmentReviewParser(json).parse(response.text(), segment);
                    assertEquals(start, review.startMs()); assertNotNull(response.usageJson());
                    return segment;
                });
            }
            var results = pool.execute(work, Instant.now().plusSeconds(360), () -> false, v -> {});
            assertEquals(2, results.size());
        }
        var output = new TreeMap<String, Object>(); output.put("newCallsThisRun", calls.get()); output.put("segments", 2);
        output.put("timings", timings); output.put("schemaAndTimeMappingValid", true);
        if (timings.size() == 2) output.put("requestsOverlapped", Math.max((long)timings.get(0).get("startEpochMs"), (long)timings.get(1).get("startEpochMs"))
                < Math.min((long)timings.get(0).get("endEpochMs"), (long)timings.get(1).get("endEpochMs")));
        json.writeValue(logs.resolve("p4-live-smoke.json").toFile(), output);
        Path first = logs.resolve("p4-live-smoke-first.json");
        if (calls.get() > 0 && !Files.exists(first)) Files.write(first, json.writeValueAsBytes(output), StandardOpenOption.CREATE_NEW);
    }
}
