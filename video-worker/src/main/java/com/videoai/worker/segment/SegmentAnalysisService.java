package com.videoai.worker.segment;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.*;
import com.videoai.common.domain.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.worker.media.*;
import com.videoai.worker.screening.SegmentReviewParser;
import com.videoai.worker.service.provider.AiVideoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** P4 服务组件：只返回收齐的片段结果，不汇总、不改父终态、不 ACK。 */
@Service
@RequiredArgsConstructor
public class SegmentAnalysisService {
    private final SegmentAnalysisExecutor executor;
    private final SegmentAnalysisProperties config;
    private final SegmentModelSettings settings;
    private final SegmentPersistenceService persistence;
    private final AiVideoProvider provider;
    private final SegmentReviewParser parser;
    private final AnalysisExecutionMapper executions;
    private final AnalysisTaskMapper tasks;
    private final StorageService storage;
    private final MediaPreparationService media;
    private final MediaProperties mediaConfig;
    private final ObjectMapper json;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public record Completed(SegmentReview review, JsonNode usage, boolean reused) {}
    public record Progress(int succeeded, int expected, List<Completed> completed) {
        public Progress { completed = List.copyOf(completed); }
    }
    public record Result(List<Completed> segments) {
        public Result { segments = List.copyOf(segments); }
        public List<SegmentReview> reviews() { return segments.stream().map(Completed::review).toList(); }
    }

    public Result analyze(String taskId, int no, Instant deadline, Consumer<Progress> progress) throws Exception {
        String runKey = taskId + ":" + no;
        if (!running.add(runKey)) throw new IOException("同一执行代次正在进行片段分析");
        try {
            config.validate(); check(taskId, no, deadline);
            AnalysisExecution execution = executions.selectExecution(taskId, no);
            if (execution == null || execution.getSegmentsObjectKey() == null) throw new IOException("片段清单尚未冻结");
            JsonNode current = json.readTree(json.writeValueAsBytes(settings.snapshot()));
            if (!current.equals(json.readTree(execution.getConfigSnapshot()).path("settings").path("segments")))
                throw new IOException("片段模型/提示词配置变化，需创建新执行代次");
            if (!current.path("model").path("supported").asBoolean()) throw new IOException("该 Provider 尚未支持片段并发路径");
            AudioPrefilterPreparationService.SegmentManifest manifest;
            try (var ws = media.open()) {
                Path file = ws.file("segments.json");
                storage.downloadToFile(execution.getSegmentsObjectKey(), file, 16 * 1024 * 1024,
                        remaining(deadline, 60), mediaConfig.getMinFreeBytes());
                manifest = json.readValue(file.toFile(), AudioPrefilterPreparationService.SegmentManifest.class);
            }
            validate(manifest.segments()); check(taskId, no, deadline);
            if (!manifest.segments().isEmpty() && (manifest.schemaVersion() != 2 || manifest.guidance() == null))
                throw new IOException("片段分析参考尚未冻结，需使用新执行代次");
            String analysisPrompt = manifest.guidance() == null ? SegmentReviewParser.VIDEO_PROMPT
                    : SegmentReviewParser.VIDEO_PROMPT + "\n以下 JSON 为用户关注点与知识参考：\n" + json.writeValueAsString(manifest.guidance());
            var rows = persistence.prepare(taskId, no, manifest.segments());
            List<Completed> results = new ArrayList<>();
            List<SegmentAnalysisExecutor.Work<Completed>> work = new ArrayList<>();
            for (var row : rows) {
                var segment = new PreparedSegment(row.getSegmentNo(), row.getStartMs(), row.getEndMs(), row.getObjectKey());
                if ("SUCCEEDED".equals(row.getStatus())) {
                    SegmentReview review = json.readValue(row.getResult(), SegmentReview.class);
                    if (review.segmentNo() != segment.segmentNo() || review.startMs() != segment.startMs() || review.endMs() != segment.endMs())
                        throw new IOException("已保存结果与片段区间不一致");
                    JsonNode receipt = row.getUsageJson() == null ? null : json.readTree(row.getUsageJson());
                    results.add(new Completed(review, receipt == null ? null : receipt.get("reportedUsage"), true));
                } else if ("PREPARED".equals(row.getStatus()) || ("PROCESSING".equals(row.getStatus()) && row.getUsageJson() != null)) {
                    work.add(scope -> analyzeOne(row, segment, scope, analysisPrompt));
                } else throw new IOException("存在失败或结果未知的片段，需核对后通过任务级重试恢复");
            }
            progress.accept(new Progress(results.size(), rows.size(), results));
            executor.execute(work, deadline, () -> tasks.isCurrentProcessing(taskId, no) != 1, value -> {
                results.add(value);
                progress.accept(new Progress(results.size(), rows.size(), results));
            });
            check(taskId, no, deadline);
            if (results.size() != manifest.segments().size()) throw new IOException("部分片段分析未完成，已保留成功结果");
            results.sort(Comparator.comparingLong(c -> c.review().startMs()));
            return new Result(results);
        } finally { running.remove(runKey); }
    }

    private Completed analyzeOne(AnalysisSegment row, PreparedSegment segment, SegmentAnalysisExecutor.Scope scope, String analysisPrompt) throws Exception {
        scope.check(); check(row.getTaskId(), row.getExecutionNo(), scope.deadline());
        // 已保存的响应只重新解析；PROCESSING 且无响应的请求绝不自动重发。
        boolean resumed = "PROCESSING".equals(row.getStatus());
        if (!resumed) {
            executor.awaitRequestPermit(scope);
            scope.write(() -> { persistence.start(row); return null; });
        }
        try {
            AiVideoProvider.DetailedResult response;
            if (resumed) response = readResponse(row, scope.deadline());
            else {
                scope.check();
                String url = storage.getPresignedUrl(segment.objectKey(), provider.getPresignedUrlExpireHours());
                response = provider.callDetailed(url, analysisPrompt);
                scope.check();
                saveResponse(row, response, scope);
            }
            if (!"stop".equals(response.finishReason())) throw new IOException("视频响应未完整结束");
            SegmentReview review = parser.parse(response.text(), segment);
            JsonNode usage = response.usageJson() == null ? null : json.readTree(response.usageJson());
            row.setStatus("SUCCEEDED"); row.setResult(json.writeValueAsString(review)); row.setErrorMessage(null);
            scope.write(() -> { persistence.finish(row); return null; });
            return new Completed(review, usage, resumed);
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) {
            // 响应已绑定时保留PROCESSING，恢复只重新解析/保存，不重新调用模型。
            throw new com.videoai.worker.processor.UnsettledTaskException("片段数据库写入尚未收敛");
        } catch (Exception e) {
            // 原始异常可能携带签名 URL；业务错误只保存固定说明，响应引用和用量仍保留。
            row.setStatus("FAILED"); row.setResult(null); row.setErrorMessage(e instanceof com.videoai.worker.screening.CandidatePlanner.InvalidTextResultException
                    ? e.getMessage() : "片段模型调用、解析或结果保存失败；请检查响应记录");
            scope.write(() -> { persistence.finish(row); return null; });
            throw new IOException("片段分析失败");
        }
    }

    private void saveResponse(AnalysisSegment row, AiVideoProvider.DetailedResult response, SegmentAnalysisExecutor.Scope scope) throws Exception {
        byte[] bytes = json.writeValueAsBytes(response);
        if (bytes.length > config.getMaxResponseBytes()) throw new IOException("片段响应超出预算");
        try (var ws = media.open()) {
            scope.check(); Path file = ws.file("response.json"); Files.write(file, bytes); ws.checkBudget();
            String key = storage.putArtifact(file, "application/json");
            var receipt = json.createObjectNode(); receipt.put("responseObjectKey", key); receipt.put("provider", provider.getName());
            receipt.set("reportedUsage", response.usageJson() == null ? json.nullNode() : json.readTree(response.usageJson()));
            row.setUsageJson(json.writeValueAsString(receipt));
            scope.write(() -> { persistence.response(row); return null; });
        }
    }
    private AiVideoProvider.DetailedResult readResponse(AnalysisSegment row, Instant deadline) throws Exception {
        String key = json.readTree(row.getUsageJson()).path("responseObjectKey").asText();
        if (key.isBlank() || key.contains("://") || key.contains("?")) throw new IOException("片段响应引用无效");
        try (var ws = media.open()) {
            Path file = ws.file("response.json");
            storage.downloadToFile(key, file, config.getMaxResponseBytes(), remaining(deadline, 60), mediaConfig.getMinFreeBytes());
            return json.readValue(file.toFile(), AiVideoProvider.DetailedResult.class);
        }
    }
    private void check(String taskId, int no, Instant deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline) || tasks.isCurrentProcessing(taskId, no) != 1)
            throw new IOException("父任务已结束、取消、超时或代次失效");
    }
    private static Duration remaining(Instant deadline, int maximumSeconds) throws IOException {
        Duration duration = Duration.between(Instant.now(), deadline);
        if (duration.isNegative() || duration.isZero()) throw new IOException("父任务期限已耗尽");
        return duration.compareTo(Duration.ofSeconds(maximumSeconds)) > 0 ? Duration.ofSeconds(maximumSeconds) : duration;
    }
    private void validate(List<PreparedSegment> segments) throws IOException {
        if (segments.size() > mediaConfig.getMaxSegments()) throw new IOException("片段数量超出预算");
        long end = 0, total = 0; int index = 0;
        for (var s : segments) {
            if (s.segmentNo() != index++ || s.startMs() < end || s.endMs() - s.startMs() > mediaConfig.getMaxSegmentMs())
                throw new IOException("冻结片段必须连续编号、有序、不重叠且在时长预算内");
            total += s.endMs() - s.startMs(); end = s.endMs();
        }
        if (total > mediaConfig.getMaxSelectedMs()) throw new IOException("片段总时长超出预算");
    }
}
