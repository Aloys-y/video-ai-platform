package com.videoai.worker.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.PreparedSegment;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 原片和媒体产物只存临时磁盘；每个工作区持有本机并发许可。 */
@Service
public class MediaPreparationService {
    private final MediaProperties properties;
    private final ObjectMapper json;
    private final Semaphore permits;

    public MediaPreparationService(MediaProperties properties, ObjectMapper json) {
        this.properties = properties; this.json = json;
        if (properties.getMaxConcurrentWorkspaces() < 1) throw new IllegalArgumentException("媒体并发必须大于0");
        permits = new Semaphore(properties.getMaxConcurrentWorkspaces(), true);
    }

    public record VideoInfo(long durationMs, long audioOffsetMs, boolean hasAudio, long videoLeadMs) {
        public VideoInfo(long durationMs, long audioOffsetMs, boolean hasAudio) { this(durationMs, audioOffsetMs, hasAudio, 0); }
    }
    public record AudioPart(int partNo, long startMs, long endMs, Path file) {}

    public Workspace open() throws IOException, InterruptedException {
        com.videoai.common.analysis.ExecutionBudget.check();
        while (!permits.tryAcquire(250, TimeUnit.MILLISECONDS))
            com.videoai.common.analysis.ExecutionBudget.check();
        try {
            com.videoai.common.analysis.ExecutionBudget.check();
            Path root = Path.of(properties.getTempRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            return new Workspace(Files.createTempDirectory(root, "execution-"));
        } catch (IOException | RuntimeException e) { permits.release(); throw e; }
    }

    public final class Workspace implements AutoCloseable {
        private final Path directory;
        private boolean closed;
        private Workspace(Path directory) { this.directory = directory; }
        public Path file(String name) {
            if (!name.matches("[A-Za-z0-9._-]+") || name.equals(".") || name.equals(".."))
                throw new IllegalArgumentException("无效临时文件名");
            if (closed) throw new IllegalStateException("工作区已关闭");
            return directory.resolve(name);
        }
        public void checkBudget() throws IOException {
            com.videoai.common.analysis.ExecutionBudget.check();
            long bytes = 0;
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) bytes += Files.size(path);
            }
            if (bytes > properties.getMaxWorkspaceBytes() || Files.getFileStore(directory).getUsableSpace() < properties.getMinFreeBytes())
                throw new IOException("媒体临时磁盘预算不足");
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                // 仅清理自身随机目录中的直接文件，不跟随符号链接或递归删除计算路径。
                try (var paths = Files.list(directory)) {
                    for (Path path : paths.toList()) Files.deleteIfExists(path);
                }
                Files.deleteIfExists(directory);
            } finally { permits.release(); }
        }
    }

    public VideoInfo probe(Workspace workspace, Path source) throws IOException, InterruptedException {
        JsonNode root = json.readTree(run(workspace, List.of(properties.getFfprobe(), "-v", "error",
                "-show_format", "-show_streams", "-of", "json", source.toString())));
        JsonNode video = null, audio = null;
        for (JsonNode stream : root.path("streams")) {
            if (video == null && stream.path("codec_type").asText().equals("video")) video = stream;
            if (audio == null && stream.path("codec_type").asText().equals("audio")) audio = stream;
        }
        if (video == null) throw new IOException("输入没有视频轨道");
        long containerDuration = millis(root.path("format").path("duration").asText());
        String origin = root.path("format").path("start_time").asText("0");
        long videoStart = millis(video.path("start_time").asText(origin));
        long videoLead = videoStart - millis(origin);
        long duration = containerDuration - videoLead;
        if (videoLead < 0 || videoLead > 60_000 || duration <= 0 || duration > properties.getMaxVideoDurationMs())
            throw new IOException("原视频时长或起点超出限制");
        long offset = audio == null ? 0 : millis(audio.path("start_time").asText(origin)) - videoStart;
        return new VideoInfo(duration, offset, audio != null, videoLead);
    }

    static long millis(String value) throws IOException {
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || Math.abs(seconds) > 864000) throw new NumberFormatException();
            return Math.round(seconds * 1000);
        } catch (NumberFormatException e) { throw new IOException("媒体时间戳无效"); }
    }

    public List<AudioPart> extractAudio(Workspace ws, Path source, VideoInfo info) throws IOException, InterruptedException {
        if (!info.hasAudio()) return List.of();
        if (Math.abs(info.audioOffsetMs()) > 60_000) throw new IOException("音视频偏移超过60秒，需要检查素材");
        long partMs = properties.getAudioPartMs();
        if (partMs < 1000 || partMs > 1_800_000) throw new IOException("音轨分段时长必须在1秒至30分钟之间");
        if ((info.durationMs() + partMs - 1) / partMs > properties.getMaxAudioParts())
            throw new IOException("音轨分段数量超过预算");
        // 先重置音频自身起点，再用补静音/裁头对齐到视频零点，避免重复应用容器 PTS。
        String alignment = "asetpts=PTS-STARTPTS" + (info.audioOffsetMs() >= 0
                ? ",adelay=" + info.audioOffsetMs() + ":all=1"
                : ",atrim=start=" + seconds(-info.audioOffsetMs()) + ",asetpts=PTS-STARTPTS")
                + ",aresample=16000,apad,atrim=duration=" + seconds(info.durationMs());
        Path aligned = ws.file("aligned.wav");
        run(ws, List.of(properties.getFfmpeg(), "-nostdin", "-n", "-v", "error", "-i", source.toString(),
                "-map", "0:a:0", "-vn", "-af", alignment, "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", aligned.toString()));
        List<AudioPart> parts = new ArrayList<>();
        for (long start = 0; start < info.durationMs(); start += partMs) {
            long end = Math.min(info.durationMs(), start + partMs);
            Path target = ws.file("audio-" + parts.size() + ".wav");
            run(ws, List.of(properties.getFfmpeg(), "-nostdin", "-n", "-v", "error", "-ss", seconds(start),
                    "-i", aligned.toString(), "-t", seconds(end - start), "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", target.toString()));
            verifyFile(target);
            parts.add(new AudioPart(parts.size(), start, end, target));
        }
        Files.delete(aligned);
        return List.copyOf(parts);
    }

    public Path clip(Workspace ws, Path source, VideoInfo info, int segmentNo, long startMs, long endMs)
            throws IOException, InterruptedException {
        if (segmentNo < 0 || startMs < 0 || endMs <= startMs || endMs > info.durationMs()
                || endMs - startMs > properties.getMaxSegmentMs()) throw new IOException("裁剪时间超出边界或单段预算");
        Path target = ws.file("segment-" + segmentNo + ".mp4");
        // 转码而非关键帧 copy，时间以原片播放零点计算；保留输入音视频相对时间差。
        run(ws, List.of(properties.getFfmpeg(), "-nostdin", "-n", "-v", "error", "-ss", seconds(startMs + info.videoLeadMs()),
                "-i", source.toString(), "-t", seconds(endMs - startMs), "-map", "0:v:0", "-map", "0:a:0?",
                "-vf", "scale=trunc(min(1280\\,iw)/2)*2:-2", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "28", "-c:a", "aac", "-movflags", "+faststart", target.toString()));
        verifyFile(target);
        VideoInfo clipped = probe(ws, target);
        if (Math.abs(clipped.durationMs() - (endMs - startMs)) > 1000) throw new IOException("裁剪产物时长不匹配");
        return target;
    }

    private static String seconds(long ms) { return java.math.BigDecimal.valueOf(ms, 3).toPlainString(); }
    private static void verifyFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) throw new IOException("媒体产物为空");
    }

    String run(Workspace ws, List<String> command) throws IOException, InterruptedException {
        ws.checkBudget();
        long deadline = System.nanoTime() + com.videoai.common.analysis.ExecutionBudget.limit(Duration.ofSeconds(properties.getProcessTimeoutSeconds())).toNanos();
        Process process;
        try { process = new ProcessBuilder(command).redirectErrorStream(true).start(); }
        catch (IOException e) { throw new IOException("无法启动 FFmpeg/FFprobe，请检查可执行文件配置"); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (output) { output.write(buffer, 0, Math.min(count, Math.max(0, 256 * 1024 - output.size()))); }
                }
            } catch (IOException ignored) { /* 主线程根据退出码/超时处理 */ }
        }, "media-output-drain");
        drain.setDaemon(true); drain.start();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                ws.checkBudget();
                if (System.nanoTime() >= deadline) throw new IOException("媒体进程超时");
            }
            drain.join(2000);
            if (drain.isAlive()) throw new IOException("媒体进程输出未结束");
            ws.checkBudget();
            if (process.exitValue() != 0) throw new IOException("媒体处理失败，退出码=" + process.exitValue());
            synchronized (output) { return output.toString(StandardCharsets.UTF_8); }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            process.getInputStream().close();
        }
    }
}
