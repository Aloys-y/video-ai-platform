package com.videoai.worker.asr;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.videoai.common.analysis.*;
import com.videoai.infra.cost.AiCallRecorder;
import com.videoai.infra.cost.AiCallRecorder.Outcome;
import com.videoai.worker.config.DashScopeConfig;
import okhttp3.*;
import com.videoai.infra.http.OneShotJsonBody;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;

@Service
public class DashScopeAsrClient implements CloudAsrClient {
    private final AsrProperties properties;
    private final DashScopeConfig fallback;
    private final ObjectMapper json;
    private final OkHttpClient http;
    private final AiCallRecorder recorder;

    @org.springframework.beans.factory.annotation.Autowired
    public DashScopeAsrClient(AsrProperties properties, DashScopeConfig fallback, ObjectMapper json, AiCallRecorder recorder) {
        this(properties, fallback, json, recorder, new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds())).build());
    }

    DashScopeAsrClient(AsrProperties properties, DashScopeConfig fallback, ObjectMapper json, AiCallRecorder recorder, OkHttpClient http) {
        this.properties = properties; this.fallback = fallback; this.json = json;
        this.http = http; this.recorder = Objects.requireNonNull(recorder);
    }

    static void checkUrl(String url, boolean api) throws IOException {
        URI uri;
        try { uri = URI.create(url); } catch (RuntimeException e) { throw new IOException("无效的云端地址"); }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IOException("云端地址必须为 HTTPS");
        if (api && !(host.equals("dashscope.aliyuncs.com") || host.equals("dashscope-intl.aliyuncs.com")
                || host.endsWith(".maas.aliyuncs.com"))) throw new IOException("ASR 凭据仅允许发送到百炼域名");
    }

    private JsonNode request(String url, JsonNode body, boolean auth) throws IOException {
        return request(url,body,auth,null);
    }

    private JsonNode request(String url, JsonNode body, boolean auth, AiCallContext submission) throws IOException {
        checkUrl(url, auth);
        Request.Builder request = new Request.Builder().url(url);
        if (auth) {
            String key = properties.getApiKey();
            if (key == null || key.isBlank()) key = fallback.getApiKey();
            if (key == null || key.isBlank()) throw new IOException("未配置云端 ASR 凭据");
            request.header("Authorization", "Bearer " + key);
        }
        if (body != null) request.header("X-DashScope-Async", "enable")
                .post(new OneShotJsonBody(json.writeValueAsBytes(body)));
        // GET 下载不能携带 Content-Type，否则 OSS 签名可能校验失败。
        Call call = http.newCall(request.build());
        call.timeout().timeout(com.videoai.common.analysis.ExecutionBudget.limit(Duration.ofSeconds(properties.getRequestTimeoutSeconds())).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        String callId = submission == null ? null : UUID.randomUUID().toString();
        if (callId != null) recorder.begin(callId, submission, properties.getModel());
        Outcome outcome = Outcome.UNKNOWN; AiUsage usage = AiUsage.unknown();
        String receiptId = null; boolean accepted = false;
        try {
            try { ExecutionBudget.check(); }
            catch (IOException cancelled) { outcome = Outcome.NOT_SENT; throw cancelled; }
            try (Response response = call.execute()) {
                if (response.body() == null) throw new IOException("ASR 响应为空");
                byte[] bytes = response.body().byteStream().readNBytes(16 * 1024 * 1024 + 1);
                if (bytes.length > 16 * 1024 * 1024) throw new IOException("ASR JSON 超过16MiB限制");
                JsonNode result = json.readTree(bytes);
                if (result == null || !result.isObject()) throw new IOException("ASR 响应无效");
                if (callId != null) {
                    usage = AiCallRecorder.audioUsage(result.get("usage"));
                    receiptId = result.path("request_id").asText(null);
                    if (!response.isSuccessful()) outcome = Outcome.FAILED;
                }
                if (!response.isSuccessful()) throw new IOException("ASR HTTP " + response.code());
                if (callId != null) {
                    String remoteId = result.path("output").path("task_id").asText();
                    if (!remoteId.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("ASR 未返回有效任务ID");
                    recorder.bindRemoteTask(callId,remoteId,receiptId);
                    accepted = true; // 接受异步任务不等于转写完成，保留 RUNNING。
                }
                return result;
            }
        } catch (IOException e) {
            throw new IOException("ASR 请求失败或响应无效；提交状态可能不确定");
        } finally {
            if (callId != null && !accepted) recorder.finish(callId,outcome,usage,receiptId,"ASR_SUBMISSION_INCOMPLETE");
        }
    }

    @Override public String submit(AiCallContext context, String audioUrl) throws IOException {
        Objects.requireNonNull(context);
        if(context.stage()!=AiCallContext.Stage.ASR) throw new IllegalArgumentException("ASR调用阶段不匹配");
        checkUrl(audioUrl, false);
        JsonNode body = json.valueToTree(Map.of("model", properties.getModel(),
                "input", Map.of("file_urls", List.of(audioUrl)),
                "parameters", Map.of("channel_id", List.of(0), "diarization_enabled", false)));
        JsonNode response = request(base() + "/services/audio/asr/transcription", body, true, context);
        String id = response.path("output").path("task_id").asText();
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("云端未返回有效任务ID，不能自动重提");
        return id;
    }

    private String base() { return properties.getBaseUrl().replaceAll("/+$", ""); }

    @Override public Query query(AiCallContext context, String taskId) throws IOException {
        if (!taskId.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("无效 ASR 任务ID");
        String callId = recorder.findAsrCall(context,taskId);
        JsonNode response = request(base() + "/tasks/" + taskId, null, true);
        JsonNode output = response.path("output");
        String status = output.path("task_status").asText();
        if (!Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN", "CANCELED").contains(status))
            throw new IOException("未知的 ASR 状态");
        // 先记录远端完成的费用，再校验结果地址/子任务；不同轮询的 request_id 不覆盖提交回执。
        if (Set.of("SUCCEEDED","FAILED","CANCELED","UNKNOWN").contains(status)) {
            Outcome outcome = status.equals("SUCCEEDED") ? Outcome.SUCCEEDED
                    : status.equals("UNKNOWN") ? Outcome.UNKNOWN : Outcome.FAILED;
            recorder.finish(callId,outcome,AiCallRecorder.audioUsage(response.get("usage")),null,
                    outcome==Outcome.SUCCEEDED ? null : "ASR_"+status);
        }
        String url = null;
        if (status.equals("SUCCEEDED")) {
            JsonNode results = output.path("results");
            if (!results.isArray() || results.size() != 1 || !results.get(0).path("subtask_status").asText().equals("SUCCEEDED"))
                throw new IOException("ASR 子任务失败，不能将顶层成功当作转写成功");
            url = results.get(0).path("transcription_url").asText();
            checkUrl(url, false);
        }
        return new Query(status, url, response.get("usage"));
    }

    @Override public Transcript downloadResult(String url, int partNo, long startMs, long endMs) throws IOException {
        return normalize(request(url, null, false), partNo, startMs, endMs);
    }

    public static Transcript normalize(JsonNode raw, int partNo, long startMs, long endMs) throws IOException {
        if (partNo < 0 || startMs < 0 || endMs <= startMs || !raw.path("transcripts").isArray())
            throw new IOException("ASR 缺少转写或有效时间区间");
        List<TranscriptUtterance> utterances = new ArrayList<>();
        for (JsonNode track : raw.path("transcripts")) {
            if (!track.path("sentences").isArray()) throw new IOException("ASR 缺少句级时间戳");
            for (JsonNode sentence : track.path("sentences")) {
                JsonNode begin = sentence.path("begin_time"), end = sentence.path("end_time");
                if (!begin.isIntegralNumber() || !end.isIntegralNumber() || !begin.canConvertToLong() || !end.canConvertToLong())
                    throw new IOException("ASR 时间戳不是毫秒整数");
                long b = begin.asLong(), e = end.asLong();
                if (b < 0 || e <= b || e > endMs - startMs) throw new IOException("ASR 时间戳超出音轨边界");
                String text = sentence.path("text").asText().strip();
                if (!text.isEmpty()) utterances.add(new TranscriptUtterance("p" + partNo + "-u" + utterances.size(),
                        startMs + b, startMs + e, text, track.path("channel_id").asInt(0)));
            }
        }
        utterances.sort(Comparator.comparingLong(TranscriptUtterance::startMs).thenComparingLong(TranscriptUtterance::endMs));
        return new Transcript(utterances, sanitize(raw));
    }

    static JsonNode sanitize(JsonNode input) {
        if (input.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            input.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("url") || key.contains("authorization") || key.contains("secret") || key.contains("api_key"))
                    copy.put(entry.getKey(), "[REDACTED]");
                else copy.set(entry.getKey(), sanitize(entry.getValue()));
            });
            return copy;
        }
        if (input.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode(); input.forEach(value -> copy.add(sanitize(value))); return copy;
        }
        if (input.isTextual()) return TextNode.valueOf(input.asText().replaceAll("https?://[^\\s\"<>]+", "[REDACTED_URL]"));
        return input.deepCopy();
    }
}
