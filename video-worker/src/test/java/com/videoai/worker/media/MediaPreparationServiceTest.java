package com.videoai.worker.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.AudioSystem;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MediaPreparationServiceTest {
    @TempDir Path root;

    @Test void waitingForWorkspaceRespondsToCancellationAndDoesNotLeakPermit() throws Exception {
        var properties = new MediaProperties(); properties.setTempRoot(root.toString()); properties.setMaxConcurrentWorkspaces(1);
        var service = new MediaPreparationService(properties, new ObjectMapper());
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            try (var occupied = service.open()) {
                var attempt = pool.submit(() -> {
                    try (var budget = com.videoai.common.analysis.ExecutionBudget.bind(java.time.Instant.MAX, cancelled::get)) {
                        waiting.countDown();
                        assertThrows(java.io.InterruptedIOException.class, service::open);
                    }
                });
                assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS));
                cancelled.set(true); attempt.get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
            try (var available = service.open()) { assertNotNull(available); }
        } finally { pool.shutdownNow(); }
    }

    @Test void workspaceLimitsPathsAndCleansUp() throws Exception {
        var properties = new MediaProperties(); properties.setTempRoot(root.toString()); properties.setMinFreeBytes(0);
        var service = new MediaPreparationService(properties, new ObjectMapper());
        Path file;
        try (var ws = service.open()) {
            assertThrows(IllegalArgumentException.class, () -> ws.file("../outside"));
            file = ws.file("source.mp4"); Files.writeString(file, "test");
            properties.setMaxWorkspaceBytes(2);
            assertThrows(java.io.IOException.class, ws::checkBudget);
        }
        assertFalse(Files.exists(file));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FFMPEG_PATH", matches = ".+")
    void realFfmpegAlignsDelayedAudioSplitsAndClips() throws Exception {
        var properties = new MediaProperties(); properties.setTempRoot(root.toString()); properties.setMinFreeBytes(0);
        properties.setFfmpeg(System.getenv("FFMPEG_PATH")); properties.setFfprobe(System.getenv("FFPROBE_PATH"));
        properties.setAudioPartMs(2000);
        var service = new MediaPreparationService(properties, new ObjectMapper());
        try (var ws = service.open()) {
            Path input = ws.file("source.mkv");
            service.run(ws, List.of(properties.getFfmpeg(), "-nostdin", "-n", "-v", "error", "-f", "lavfi", "-i",
                    "color=c=black:s=320x240:r=25:d=4", "-itsoffset", "0.5", "-f", "lavfi", "-i",
                    "sine=frequency=1000:sample_rate=16000:duration=3", "-c:v", "libx264", "-c:a", "pcm_s16le", input.toString()));
            var info = service.probe(ws, input);
            assertEquals(500, info.audioOffsetMs());
            var parts = service.extractAudio(ws, input, info);
            assertEquals(2, parts.size()); assertEquals(2000, parts.get(1).startMs());
            try (var wav = AudioSystem.getAudioInputStream(parts.get(0).file().toFile())) {
                byte[] pcm = wav.readAllBytes();
                assertTrue(energy(pcm, 0, 400) < 10, "延迟音轨前400ms应为补齐静音");
                assertTrue(energy(pcm, 600, 900) > 500, "600ms后应为原音轨信号");
            }
            Path clip = service.clip(ws, input, info, 0, 1000, 3000);
            assertTrue(Files.size(clip) > 0);
            assertTrue(Math.abs(service.probe(ws, clip).durationMs() - 2000) < 200);
        }
    }

    private double energy(byte[] pcm, int startMs, int endMs) {
        long sum = 0; int count = 0;
        for (int i = startMs * 32; i < Math.min(pcm.length - 1, endMs * 32); i += 2) {
            sum += Math.abs((short)((pcm[i] & 255) | (pcm[i + 1] << 8))); count++;
        }
        return (double)sum / count;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FFMPEG_PATH", matches = ".+")
    void realFfmpegTrimsAudioBeforeVideoOriginAndKillsTimeout() throws Exception {
        var properties = new MediaProperties(); properties.setTempRoot(root.toString()); properties.setMinFreeBytes(0);
        properties.setFfmpeg(System.getenv("FFMPEG_PATH")); properties.setFfprobe(System.getenv("FFPROBE_PATH"));
        var service = new MediaPreparationService(properties, new ObjectMapper());
        try (var ws = service.open()) {
            Path input = ws.file("lead.mkv");
            service.run(ws, List.of(properties.getFfmpeg(), "-nostdin", "-n", "-v", "error", "-itsoffset", "0.5",
                    "-f", "lavfi", "-i", "color=c=black:s=320x240:r=25:d=4", "-f", "lavfi", "-i",
                    "sine=frequency=1000:sample_rate=16000:duration=3,adelay=500:all=1", "-c:v", "libx264", "-c:a", "pcm_s16le", input.toString()));
            var info = service.probe(ws, input);
            // 25fps 会把0.5秒视频起点量化到相邻帧，允许一帧误差，后续使用实际探测值。
            assertTrue(Math.abs(info.audioOffsetMs() + 500) <= 40);
            assertEquals(-info.audioOffsetMs(), info.videoLeadMs());
            assertTrue(Math.abs(info.durationMs() - 4000) <= 40);
            var parts = service.extractAudio(ws, input, info);
            try (var wav = AudioSystem.getAudioInputStream(parts.get(0).file().toFile())) {
                assertTrue(energy(wav.readAllBytes(), 0, 200) > 500, "视频零点前的音频应已裁去");
            }
            assertTrue(Files.size(service.clip(ws, input, info, 0, 0, 1000)) > 0);
            properties.setProcessTimeoutSeconds(1);
            var error = assertThrows(java.io.IOException.class, () -> service.run(ws, List.of(properties.getFfmpeg(),
                    "-nostdin", "-v", "error", "-re", "-f", "lavfi", "-i", "color=c=black:s=32x32", "-f", "null", "-")));
            assertTrue(error.getMessage().contains("超时"));
        }
    }
}
