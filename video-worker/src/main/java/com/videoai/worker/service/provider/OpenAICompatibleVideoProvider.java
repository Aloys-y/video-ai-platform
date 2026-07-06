package com.videoai.worker.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.worker.config.OpenAICompatibleConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * OpenAI兼容协议Provider
 *
 * 通过标准 OpenAI Chat Completions API（/v1/chat/completions）调用自部署模型。
 *
 * 两种媒体输入模式：
 * 1. video_url（默认）：直接传视频URL，由模型服务端处理视频解码——vLLM Qwen2.5-VL支持此模式
 * 2. image_frames：Worker本地FFmpeg抽帧 → base64图片数组 → 发送给模型——通用模式，适合只吃图片的模型
 *
 * 支持场景：
 * - vLLM 部署的 Qwen2.5-VL / InternVL2.5 / QLoRA微调模型
 * - Ollama 部署的视觉模型
 * - 任何兼容 OpenAI Vision API 格式的模型服务
 *
 * 激活方式：ai.provider=openai-compatible
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.provider", havingValue = "openai-compatible")
public class OpenAICompatibleVideoProvider implements AiVideoProvider {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final OpenAICompatibleConfig config;
    private HttpClient httpClient;

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeout()))
                .build();
        log.info("OpenAICompatibleVideoProvider initialized - baseUrl: {}, model: {}, mediaInputType: {}",
                config.getBaseUrl(), config.getModel(), config.getMediaInputType());
    }

    // ======================== 主入口 ========================

    @Override
    public String call(String videoUrl, String prompt) throws AiProviderException {
        if ("image_frames".equals(config.getMediaInputType())) {
            return callWithImageFrames(videoUrl, prompt);
        }
        return callWithVideoUrl(videoUrl, prompt);
    }

    @Override
    public int getPresignedUrlExpireHours() {
        return config.getPresignedUrlExpireHours();
    }

    @Override
    public String getName() {
        return "OpenAICompatible(" + config.getModel() + ")";
    }

    // ======================== video_url 模式 ========================

    /**
     * video_url 模式：直接将视频URL传给模型服务端
     *
     * 适用于 vLLM 部署的 Qwen2.5-VL，服务端内部处理视频解码。
     */
    private String callWithVideoUrl(String videoUrl, String prompt) throws AiProviderException {
        log.info("OpenAI-compatible [video_url] request - model: {}, videoUrl: {}", config.getModel(), videoUrl);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("max_tokens", config.getMaxTokens());

        Map<String, Object> videoContent = new LinkedHashMap<>();
        videoContent.put("type", "video_url");
        Map<String, Object> videoUrlObj = new LinkedHashMap<>();
        videoUrlObj.put("url", videoUrl);
        videoContent.put("video_url", videoUrlObj);

        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", prompt);

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", Arrays.asList(videoContent, textContent));

        requestBody.put("messages", Collections.singletonList(userMessage));

        return doApiCall(requestBody);
    }

    // ======================== image_frames 模式 ========================

    /**
     * image_frames 模式：Worker本地FFmpeg抽帧 → base64图片数组 → 发送给模型
     *
     * 适用于只支持 image 输入的自部署模型（Ollama、部分vLLM配置等）。
     * 流程：下载视频 → FFmpeg抽帧 → base64编码 → 构建请求 → API调用
     */
    private String callWithImageFrames(String videoUrl, String prompt) throws AiProviderException {
        log.info("OpenAI-compatible [image_frames] request - model: {}, videoUrl: {}, frameInterval: {}s, maxFrames: {}",
                config.getModel(), videoUrl, config.getFrameInterval(), config.getMaxFrames());

        Path tempBaseDir;
        try {
            tempBaseDir = Paths.get(config.getTempDir());
            Files.createDirectories(tempBaseDir);
        } catch (IOException e) {
            throw new AiProviderException("Failed to create temp dir: " + config.getTempDir(), e, false);
        }

        Path tempVideoFile = null;
        Path framesDir = null;
        try {
            // 下载视频到临时文件
            String uuid = UUID.randomUUID().toString().substring(0, 8);
            tempVideoFile = tempBaseDir.resolve("video_" + uuid + ".mp4");
            framesDir = tempBaseDir.resolve("frames_" + uuid);

            downloadVideo(videoUrl, tempVideoFile);

            // 3. FFmpeg抽帧
            List<Path> frameFiles = extractFrames(tempVideoFile, framesDir);

            if (frameFiles.isEmpty()) {
                throw new AiProviderException("FFmpeg extracted 0 frames from video", false);
            }

            // 4. 限制帧数（视频太长时取最后的帧以保证覆盖全片）
            if (frameFiles.size() > config.getMaxFrames()) {
                frameFiles = sampleFrames(frameFiles, config.getMaxFrames());
            }

            log.info("Sending {} frames to model", frameFiles.size());

            // 5. 构建带base64图片的请求体并发起API调用
            Map<String, Object> requestBody = buildImageFramesRequestBody(frameFiles, prompt);
            return doApiCall(requestBody);

        } finally {
            // 清理临时文件
            cleanup(tempVideoFile, framesDir);
        }
    }

    /**
     * 从临时URL下载视频文件
     */
    private void downloadVideo(String videoUrl, Path targetPath) throws AiProviderException {
        log.info("Downloading video from presigned URL...");
        try {
            HttpRequest downloadReq = HttpRequest.newBuilder()
                    .uri(URI.create(videoUrl))
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(downloadReq,
                    HttpResponse.BodyHandlers.ofFile(targetPath));

            if (response.statusCode() != 200) {
                throw new AiProviderException(
                        "Failed to download video, HTTP " + response.statusCode(), false);
            }

            long sizeMb = Files.size(targetPath) / (1024 * 1024);
            log.info("Video downloaded: {} MB", sizeMb);
        } catch (AiProviderException e) {
            throw e;
        } catch (IOException e) {
            throw new AiProviderException("Failed to download video: " + e.getMessage(), e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Video download interrupted", e, true);
        }
    }

    /**
     * 使用 FFmpeg 抽取关键帧
     *
     * @return 按文件名排序的帧文件列表
     */
    private List<Path> extractFrames(Path videoFile, Path framesDir) throws AiProviderException {
        try {
            Files.createDirectories(framesDir);
        } catch (IOException e) {
            throw new AiProviderException("Failed to create frames dir: " + framesDir, e, false);
        }

        String scaleFilter = String.format("scale=%d:%d:force_original_aspect_ratio=decrease",
                config.getMaxFrameWidth(), config.getMaxFrameHeight());

        String fpsFilter = "fps=1/" + config.getFrameInterval();

        List<String> cmd = List.of(
                config.getFfmpegPath(),
                "-i", videoFile.toString(),
                "-vf", fpsFilter + "," + scaleFilter,
                "-q:v", "2",
                framesDir.resolve("frame_%04d.jpg").toString()
        );

        log.info("Running FFmpeg: {}", String.join(" ", cmd));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(config.getTimeout(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AiProviderException("FFmpeg timed out after " + config.getTimeout() + "s", true);
            }

            if (process.exitValue() != 0) {
                String stderr = new String(process.getInputStream().readAllBytes());
                throw new AiProviderException("FFmpeg exited with " + process.exitValue() + ": " + truncate(stderr, 500), false);
            }
        } catch (AiProviderException e) {
            throw e;
        } catch (IOException e) {
            throw new AiProviderException("FFmpeg not found or I/O error: " + e.getMessage()
                    + " - please install ffmpeg or set ai.openai-compatible.ffmpeg-path", e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("FFmpeg interrupted", e, true);
        }

        // 收集并按文件名排序帧文件
        try (Stream<Path> stream = Files.list(framesDir)) {
            List<Path> frames = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            log.info("FFmpeg extracted {} frames", frames.size());
            return frames;
        } catch (IOException e) {
            throw new AiProviderException("Failed to list frames: " + e.getMessage(), e, false);
        }
    }

    /**
     * 均匀采样帧，确保覆盖视频全时间范围
     */
    private List<Path> sampleFrames(List<Path> frames, int maxFrames) {
        if (frames.size() <= maxFrames) return frames;

        List<Path> sampled = new ArrayList<>(maxFrames);
        double step = (double) (frames.size() - 1) / (maxFrames - 1);
        for (int i = 0; i < maxFrames; i++) {
            int idx = (int) Math.round(i * step);
            sampled.add(frames.get(idx));
        }
        log.info("Sampled {} frames from {} total", sampled.size(), frames.size());
        return sampled;
    }

    /**
     * 构建 image_frames 模式的请求体
     *
     * content 数组格式：[{type: "image_url", image_url: {url: "data:image/jpeg;base64,..."}}, ..., {type: "text", text: "..."}]
     */
    private Map<String, Object> buildImageFramesRequestBody(List<Path> frameFiles, String prompt)
            throws AiProviderException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());

        List<Map<String, Object>> content = new ArrayList<>();

        // 每个帧作为一个 image_url 块
        for (Path frameFile : frameFiles) {
            String base64;
            try {
                byte[] bytes = Files.readAllBytes(frameFile);
                base64 = Base64.getEncoder().encodeToString(bytes);
            } catch (IOException e) {
                throw new AiProviderException("Failed to read frame: " + frameFile, e, false);
            }

            Map<String, Object> imageContent = new LinkedHashMap<>();
            imageContent.put("type", "image_url");
            imageContent.put("image_url", Map.of("url", "data:image/jpeg;base64," + base64));
            content.add(imageContent);
        }

        // 给模型补充帧率上下文
        String framedPrompt = String.format(
                "[以下是视频的关键帧截图，每%d秒一帧，共%d帧]\n\n%s",
                config.getFrameInterval(), frameFiles.size(), prompt);

        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", framedPrompt);
        content.add(textContent);

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        body.put("messages", Collections.singletonList(userMessage));
        return body;
    }

    // ======================== 通用：API调用与响应解析 ========================

    /**
     * 执行实际的 HTTP API 调用
     */
    private String doApiCall(Map<String, Object> requestBody) throws AiProviderException {
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new AiProviderException("Failed to serialize request body", e, false);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.getTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson));

        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("API I/O error - message: {}", e.getMessage());
            throw new AiProviderException(
                    "I/O error: " + e.getMessage(), e, isTransientError(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Request interrupted", e, true);
        }

        int statusCode = response.statusCode();
        String responseBody = response.body();

        if (statusCode >= 200 && statusCode < 300) {
            return parseResponse(responseBody);
        }

        log.error("API HTTP error - status: {}, body: {}", statusCode, truncate(responseBody, 500));

        boolean retryable = statusCode == 429 || statusCode >= 500
                || isTransientError(responseBody);
        throw new AiProviderException(
                "API HTTP " + statusCode + ": " + truncate(responseBody, 200), retryable);
    }

    /**
     * 解析 OpenAI Chat Completions 响应
     *
     * 标准格式：{"choices": [{"message": {"content": "..."}}]}
     */
    private String parseResponse(String responseBody) throws AiProviderException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiProviderException("API returned empty choices", false);
            }

            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText();

            if (content == null || content.isEmpty()) {
                String finishReason = choices.get(0).path("finish_reason").asText();
                throw new AiProviderException(
                        "API returned empty content, finish_reason=" + finishReason, false);
            }

            log.info("OpenAI-compatible API response length: {}", content.length());
            return content;
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse API response: {}", responseBody, e);
            throw new AiProviderException(
                    "Failed to parse response: " + e.getMessage(), e, false);
        }
    }

    // ======================== 工具方法 ========================

    /**
     * 清理临时文件
     */
    private void cleanup(Path videoFile, Path framesDir) {
        try {
            if (videoFile != null) {
                Files.deleteIfExists(videoFile);
            }
            if (framesDir != null && Files.exists(framesDir)) {
                try (Stream<Path> stream = Files.walk(framesDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean up temp files: {}", e.getMessage());
        }
    }

    private boolean isTransientError(String message) {
        if (message == null) return false;
        return message.contains("timed out") || message.contains("timeout")
                || message.contains("Connection reset") || message.contains("connection reset")
                || message.contains("connect timed out");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
