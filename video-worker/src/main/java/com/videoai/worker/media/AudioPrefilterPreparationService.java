package com.videoai.worker.media;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.*;
import com.videoai.common.domain.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.worker.asr.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** P2 可组合服务：返回转写/片段清单，不修改任务终态，不 ACK，也不触发模型视频分析。 */
@Service
@RequiredArgsConstructor
public class AudioPrefilterPreparationService {
    private final MediaPreparationService media;
    private final MediaProperties mediaConfig;
    private final AsrProperties asrConfig;
    private final com.videoai.worker.screening.TextAnalysisProperties textConfig;
    private final com.videoai.worker.segment.SegmentModelSettings segmentSettings;
    private final CloudAsrClient asr;
    private final StorageService storage;
    private final AnalysisExecutionMapper executions;
    private final AnalysisAsrPartMapper parts;
    private final AnalysisTaskMapper tasks;
    private final AsrPartPersistenceService persistence;
    private final ObjectMapper json;

    public record TranscriptManifest(int schemaVersion, long durationMs, String outcome,
                                     List<TranscriptUtterance> utterances) {
        public TranscriptManifest { utterances = List.copyOf(utterances); }
    }
    public record SegmentManifest(int schemaVersion, List<PreparedSegment> segments, SegmentGuidance guidance) {
        public SegmentManifest(int schemaVersion, List<PreparedSegment> segments) { this(schemaVersion, segments, null); }
        public SegmentManifest { segments = List.copyOf(segments); }
    }
    public record Range(long startMs, long endMs) {}
    @FunctionalInterface public interface GuidanceLoader { SegmentGuidance load() throws IOException; }

    public TranscriptManifest prepareTranscript(String taskId, int executionNo, String sourceObjectKey)
            throws IOException, InterruptedException {
        try (var ws = openWorkspace()) {
            return prepareTranscript(taskId, executionNo, sourceObjectKey, ws);
        }
    }

    public MediaPreparationService.Workspace openWorkspace() throws IOException, InterruptedException { return media.open(); }

    public TranscriptManifest prepareTranscript(String taskId, int executionNo, String sourceObjectKey,
            MediaPreparationService.Workspace ws) throws IOException, InterruptedException {
        validateIdentity(taskId, executionNo, sourceObjectKey);
        active(taskId, executionNo, "PREPARING_AUDIO");
        {
            AnalysisExecution execution = executions.selectExecution(taskId, executionNo);
            Path source = ws.file("source.mp4");
            MediaPreparationService.VideoInfo info;
            if (execution == null) {
                downloadSource(sourceObjectKey, source);
                info = media.probe(ws, source);
                execution = new AnalysisExecution(); execution.setTaskId(taskId); execution.setExecutionNo(executionNo);
                execution.setAnalysisMode("AUDIO_PREFILTER"); execution.setInputHash(sha256(source));
                String snapshot = json.writeValueAsString(new TreeMap<>(Map.of("settings", settings(), "sourceObjectKey", sourceObjectKey, "video", info)));
                execution.setConfigSnapshot(snapshot); execution.setConfigHash(sha256(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if (executions.insert(execution) != 1) throw new IOException("执行快照创建失败或任务不是粗筛模式");
            } else {
                JsonNode snapshot = json.readTree(execution.getConfigSnapshot());
                if (!matchesSettings(snapshot)
                        || !snapshot.path("sourceObjectKey").asText().equals(sourceObjectKey))
                    throw new IOException("执行配置或原片引用变化，请创建新执行代次");
                info = json.treeToValue(snapshot.path("video"), MediaPreparationService.VideoInfo.class);
                if (execution.getTranscriptObjectKey() != null)
                    return readArtifact(ws, execution.getTranscriptObjectKey(), TranscriptManifest.class);
            }
            // 新代次已重新核对原片 SHA-256 与完整配置，显式绑定相同不可变产物。
            var reusableExecution = executions.selectReusable(taskId, executionNo);
            if (reusableExecution != null) {
                bindReused(taskId, executionNo, AnalysisExecutionMapper.Artifact.TRANSCRIPT, reusableExecution.getTranscriptObjectKey());
                if (reusableExecution.getCandidatesObjectKey() != null) {
                    bindReused(taskId, executionNo, AnalysisExecutionMapper.Artifact.CANDIDATES, reusableExecution.getCandidatesObjectKey());
                    if (reusableExecution.getSegmentsObjectKey() != null)
                        bindReused(taskId, executionNo, AnalysisExecutionMapper.Artifact.SEGMENTS, reusableExecution.getSegmentsObjectKey());
                }
                return readArtifact(ws, reusableExecution.getTranscriptObjectKey(), TranscriptManifest.class);
            }
            if (!info.hasAudio()) return persistTranscript(ws, execution, new TranscriptManifest(1, info.durationMs(), "NO_AUDIO", List.of()));
            if (java.math.BigDecimal.valueOf(info.durationMs(),3).multiply(textConfig.getAsrCnyPerSecond())
                    .compareTo(textConfig.getMaxEstimatedTaskCny())>0) throw new IOException("原片转写预估费用已超出整局预算");
            List<AnalysisAsrPart> plan = parts.selectExecution(taskId, executionNo);
            if (plan.isEmpty()) {
                List<AnalysisAsrPart> reusable = parts.selectReusablePlan(taskId, executionNo, execution.getInputHash(), execution.getConfigSnapshot());
                if (!reusable.isEmpty()) {
                    verifyPlan(reusable, info.durationMs());
                    for (AnalysisAsrPart part : reusable) {
                        int sourceNo = part.getExecutionNo();
                        if (part.getAsrTaskId() != null && !part.getAsrTaskId().equals("SUBMITTING") && part.getTranscriptObjectKey() == null) {
                            var state = asr.query(part.getAsrTaskId());
                            // 用户创建了新代次，只有确定失败/取消的远端任务才允许重新提交。
                            if (Set.of("FAILED", "CANCELED").contains(state.status())) part.setAsrTaskId(null);
                        }
                        part.setExecutionNo(executionNo); part.setReusedExecutionNo(sourceNo); part.setUsageJson(null);
                    }
                    persistence.savePlan(reusable); plan = reusable;
                }
            }
            if (plan.isEmpty()) {
                if (!Files.exists(source)) downloadSource(sourceObjectKey, source);
                if (!sha256(source).equals(execution.getInputHash())) throw new IOException("原片内容已变化，拒绝复用旧执行");
                List<AnalysisAsrPart> newPlan = new ArrayList<>();
                for (var audio : media.extractAudio(ws, source, info)) {
                    active(taskId, executionNo, "PREPARING_AUDIO");
                    var part = new AnalysisAsrPart(); part.setTaskId(taskId); part.setExecutionNo(executionNo);
                    part.setPartNo(audio.partNo()); part.setStartMs(audio.startMs()); part.setEndMs(audio.endMs());
                    part.setAudioObjectKey(storage.putArtifact(audio.file(), "audio/wav"));
                    newPlan.add(part);
                }
                persistence.savePlan(newPlan); plan = newPlan;
            }
            verifyPlan(plan, info.durationMs());
            long deadline = System.nanoTime() + ExecutionBudget.limit(Duration.ofSeconds(asrConfig.getMaxWaitSeconds())).toNanos();
            List<TranscriptUtterance> utterances = new ArrayList<>();
            for (AnalysisAsrPart part : plan) {
                active(taskId, executionNo, "TRANSCRIBING");
                if (part.getTranscriptObjectKey() != null) {
                    utterances.addAll(readArtifact(ws, part.getTranscriptObjectKey(), CloudAsrClient.Transcript.class).utterances());
                    continue;
                }
                if (part.getAsrTaskId() == null) {
                    if (System.nanoTime() >= deadline) throw new IOException("本次转写等待预算已耗尽");
                    // 先保存 SUBMITTING，再请求云端。结果未知时保留占位，禁止自动二次扣费。
                    String url = storage.getPresignedUrl(part.getAudioObjectKey(), asrConfig.getSignedUrlHours());
                    if (parts.claimSubmission(part) != 1) throw new IOException("该音轨已被提交或执行失效");
                    part.setAsrTaskId(asr.submit(url));
                    if (parts.recordSubmitted(part) != 1) throw new IOException("远端ID保存失败，需要人工核对转写任务");
                    if (part.getPartNo() == 0) executions.bindOnce(taskId, executionNo,
                            AnalysisExecutionMapper.Artifact.ASR_TASK_ID, part.getAsrTaskId());
                }
                if ("SUBMITTING".equals(part.getAsrTaskId())) throw new IOException("ASR 提交结果未知，请核对远端任务，禁止自动重提");
                CloudAsrClient.Query result = waitForResult(part, deadline);
                CloudAsrClient.Transcript transcript = asr.downloadResult(result.resultUrl(), part.getPartNo(), part.getStartMs(), part.getEndMs());
                active(taskId, executionNo, "TRANSCRIBING");
                part.setTranscriptObjectKey(writeArtifact(ws, transcript));
                part.setUsageJson(result.usage() == null ? null : json.writeValueAsString(result.usage()));
                if (parts.recordResult(part) != 1) throw new IOException("转写结果保存冲突或执行已失效");
                utterances.addAll(transcript.utterances());
            }
            utterances.sort(Comparator.comparingLong(TranscriptUtterance::startMs).thenComparingLong(TranscriptUtterance::endMs));
            return persistTranscript(ws, execution, new TranscriptManifest(1, info.durationMs(),
                    utterances.isEmpty() ? "NO_SPEECH" : "TRANSCRIBED", utterances));
        }
    }

    private CloudAsrClient.Query waitForResult(AnalysisAsrPart part, long deadline) throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            active(part.getTaskId(), part.getExecutionNo(), "TRANSCRIBING");
            var result = asr.query(part.getAsrTaskId());
            if (result.status().equals("SUCCEEDED")) return result;
            if (!Set.of("PENDING", "RUNNING").contains(result.status())) throw new IOException("ASR 任务终止：" + result.status());
            long remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime())).toMillis();
            Thread.sleep(Math.min(remaining, asrConfig.getPollIntervalSeconds() * 1000L));
        }
        throw new IOException("ASR 查询超时，已保留远端任务ID；不代表远端已取消");
    }

    private void active(String taskId, int no, String step) throws IOException {
        ExecutionBudget.check();
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("处理已中断");
        if (tasks.updateStep(taskId, no, step) != 1) throw new IOException("任务已取消、结束或执行代次变化");
    }

    private void bindReused(String taskId, int no, AnalysisExecutionMapper.Artifact artifact, String key) throws IOException {
        ExecutionBudget.check();
        if (executions.bindOnce(taskId, no, artifact, key) != 1) throw new IOException("历史产物复用被拒绝或当前执行已失效");
    }

    private TranscriptManifest persistTranscript(MediaPreparationService.Workspace ws, AnalysisExecution execution,
                                                   TranscriptManifest manifest) throws IOException {
        active(execution.getTaskId(), execution.getExecutionNo(), "TRANSCRIBING");
        String key = writeArtifact(ws, manifest);
        if (executions.bindOnce(execution.getTaskId(), execution.getExecutionNo(), AnalysisExecutionMapper.Artifact.TRANSCRIPT, key) != 1)
            throw new IOException("转写清单保存冲突或执行已失效");
        return manifest;
    }

    public SegmentManifest prepareSegments(String taskId, int no, String sourceObjectKey, List<Range> ranges)
            throws IOException, InterruptedException {
        return prepareSegments(taskId, no, sourceObjectKey, ranges, () -> new SegmentGuidance("", ""));
    }

    public SegmentManifest prepareSegments(String taskId, int no, String sourceObjectKey, List<Range> ranges,
            GuidanceLoader guidance) throws IOException, InterruptedException {
        try (var ws = openWorkspace()) {
            return prepareSegments(taskId, no, sourceObjectKey, ranges, guidance, ws);
        }
    }

    public SegmentManifest prepareSegments(String taskId, int no, String sourceObjectKey, List<Range> ranges,
            GuidanceLoader guidance, MediaPreparationService.Workspace ws) throws IOException, InterruptedException {
        validateIdentity(taskId, no, sourceObjectKey); ranges = List.copyOf(ranges);
        AnalysisExecution execution = executions.selectExecution(taskId, no);
        if (execution == null || execution.getTranscriptObjectKey() == null || execution.getCandidatesObjectKey() == null)
            throw new IOException("应先完成转写并冻结粗筛候选清单");
        JsonNode snapshot = json.readTree(execution.getConfigSnapshot());
        if (!snapshot.path("sourceObjectKey").asText().equals(sourceObjectKey)
                || !matchesSettings(snapshot)) throw new IOException("裁剪配置与执行快照不匹配");
        var info = json.treeToValue(snapshot.path("video"), MediaPreparationService.VideoInfo.class);
        validateRanges(ranges, info.durationMs());
        active(taskId, no, "PREPARING_SEGMENTS");
        {
            var screening = readArtifact(ws, execution.getCandidatesObjectKey(),
                    com.videoai.worker.screening.TextScreeningService.ScreeningManifest.class);
            if (!execution.getConfigHash().equals(screening.configHash()) || !ranges.equals(screening.ranges()))
                throw new IOException("裁剪区间与冻结的粗筛清单不一致");
            if (execution.getSegmentsObjectKey() != null) {
                SegmentManifest saved = readArtifact(ws, execution.getSegmentsObjectKey(), SegmentManifest.class);
                List<Range> prior = saved.segments().stream().map(s -> new Range(s.startMs(), s.endMs())).toList();
                if (!prior.equals(ranges)) throw new IOException("片段清单已冻结，拒绝覆盖不同区间");
                // 恢复时逐项检查对象仍存在且可读取，不静默返回失效对象键。
                for (var segment : saved.segments()) {
                    Path check = ws.file("check-" + segment.segmentNo() + ".mp4");
                    storage.downloadToFile(segment.objectKey(), check, mediaConfig.getMaxSourceBytes(), Duration.ofMinutes(10), mediaConfig.getMinFreeBytes());
                    media.probe(ws, check); Files.delete(check);
                }
                return saved;
            }
            List<PreparedSegment> prepared = new ArrayList<>();
            if (!ranges.isEmpty()) {
                Path source = ws.file("source.mp4");
                if (!Files.exists(source)) downloadSource(sourceObjectKey, source);
                if (!sha256(source).equals(execution.getInputHash())) throw new IOException("原片内容与快照不一致");
                for (Range range : ranges) {
                    active(taskId, no, "PREPARING_SEGMENTS");
                    Path clip = media.clip(ws, source, info, prepared.size(), range.startMs(), range.endMs());
                    String key = storage.putArtifact(clip, "video/mp4");
                    prepared.add(new PreparedSegment(prepared.size(), range.startMs(), range.endMs(), key));
                    Files.delete(clip);
                }
            }
            // 只在首次冻结非空清单时检索；恢复直接使用已保存的参考。
            SegmentManifest manifest = new SegmentManifest(2, prepared, prepared.isEmpty() ? null : Objects.requireNonNull(guidance.load()));
            String key = writeArtifact(ws, manifest);
            if (executions.bindOnce(taskId, no, AnalysisExecutionMapper.Artifact.SEGMENTS, key) != 1)
                throw new IOException("片段清单保存冲突或执行已失效");
            return manifest;
        }
    }

    void validateRanges(List<Range> ranges, long durationMs) throws IOException {
        if (ranges.size() > mediaConfig.getMaxSegments()) throw new IOException("片段数量超过预算");
        long total = 0, lastEnd = 0;
        for (Range r : ranges) {
                if (r.startMs() < lastEnd || r.endMs() <= r.startMs() || r.endMs() > durationMs
                    || r.endMs() - r.startMs() > mediaConfig.getMaxSegmentMs()) throw new IOException("片段必须有序、不重叠且在原片和单段预算内");
            total += r.endMs() - r.startMs(); lastEnd = r.endMs();
        }
        if (total > mediaConfig.getMaxSelectedMs()) throw new IOException("候选总时长超过预算");
    }

    static void verifyPlan(List<AnalysisAsrPart> plan, long durationMs) throws IOException {
        long end = 0; int no = 0;
        for (AnalysisAsrPart p : plan) {
            if (p.getPartNo() != no++ || p.getStartMs() != end || p.getEndMs() <= end) throw new IOException("音轨清单不完整或时间不连续");
            end = p.getEndMs();
        }
        if (end != durationMs) throw new IOException("音轨清单没有覆盖整段视频");
    }

    private void downloadSource(String key, Path file) throws IOException {
        storage.downloadToFile(key, file, Math.min(mediaConfig.getMaxSourceBytes(), mediaConfig.getMaxWorkspaceBytes()),
                Duration.ofMinutes(10), mediaConfig.getMinFreeBytes());
    }
    private String writeArtifact(MediaPreparationService.Workspace ws, Object value) throws IOException {
        Path path = ws.file("manifest-" + UUID.randomUUID() + ".json");
        json.writeValue(path.toFile(), value); ws.checkBudget();
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("JSON 产物超过16MiB预算");
        String key = storage.putArtifact(path, "application/json"); Files.delete(path); return key;
    }
    private <T> T readArtifact(MediaPreparationService.Workspace ws, String key, Class<T> type) throws IOException {
        Path file = ws.file("read-" + UUID.randomUUID() + ".json");
        storage.downloadToFile(key, file, 16 * 1024 * 1024, Duration.ofSeconds(60), mediaConfig.getMinFreeBytes());
        T value = json.readValue(file.toFile(), type); Files.delete(file); return value;
    }
    private Map<String, Object> settings() {
        Map<String,Object> settings=new TreeMap<>(Map.of("version", "p4-v1", "asrModel", asrConfig.getModel(), "asrBaseUrl", asrConfig.getBaseUrl(),
                "audioPartMs", mediaConfig.getAudioPartMs(), "audioCodec", "pcm_s16le-mono-16000",
                "clipCodec", "libx264-veryfast-crf28-max1280-aac", "maxVideoDurationMs", mediaConfig.getMaxVideoDurationMs(),
                "maxSegmentMs", mediaConfig.getMaxSegmentMs(), "maxSelectedMs", mediaConfig.getMaxSelectedMs(), "maxSegments", mediaConfig.getMaxSegments()));
        settings.put("text",textConfig.snapshot());
        settings.put("segments",segmentSettings.snapshot());
        return settings;
    }
    private boolean matchesSettings(JsonNode snapshot) throws IOException {
        // 统一经 JSON 反序列化，避免 LongNode/IntNode 在相同数值下比较不等。
        return snapshot.path("settings").equals(json.readTree(json.writeValueAsBytes(settings())));
    }
    private static void validateIdentity(String taskId, int no, String key) {
        if (taskId == null || taskId.isBlank() || no < 0 || key == null || key.isBlank() || key.contains("://") || key.contains("?"))
            throw new IllegalArgumentException("需要有效任务代次与对象键，不接受签名URL");
    }
    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = input.read(buffer)) != -1) { ExecutionBudget.check(); digest.update(buffer, 0, n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
