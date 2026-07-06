package com.videoai.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI兼容协议Provider配置
 *
 * 支持任何兼容OpenAI Chat Completions API的自部署模型服务，包括：
 * vLLM / Ollama / LMDeploy / Xinference / LocalAI 等部署的视觉语言模型
 *
 * 典型场景：QLoRA微调 Qwen2.5-VL / InternVL2.5 后通过 vLLM 部署，接入本系统
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.openai-compatible")
public class OpenAICompatibleConfig {

    /** API Base URL（如 http://your-gpu:8000/v1） */
    private String baseUrl = "http://localhost:8000/v1";

    /** API Key（自部署通常不需要，填空字符串即可） */
    private String apiKey = "";

    /** 模型名称（vLLM部署时的 --served-model-name） */
    private String model = "Qwen2.5-VL-7B-Instruct";

    /** 最大输出Token数 */
    private int maxTokens = 4096;

    /** 读取超时（秒），视频分析比较慢，给足时间 */
    private int timeout = 300;

    /** 连接超时（秒） */
    private int connectTimeout = 30;

    /** MinIO预签名URL过期时间（小时） */
    private int presignedUrlExpireHours = 2;

    /**
     * 媒体输入类型：
     * - video_url: 直接传视频URL给模型（vLLM Qwen2.5-VL支持）
     * - image_frames: 本地FFmpeg抽帧 → base64图片数组（通用模式，适合只吃图片的模型）
     */
    private String mediaInputType = "video_url";

    // ---- image_frames 模式专用参数 ----

    /** FFmpeg可执行文件路径 */
    private String ffmpegPath = "ffmpeg";

    /** 抽帧间隔（秒），默认每2秒抽一帧 */
    private int frameInterval = 2;

    /** 抽帧最大宽度（像素） */
    private int maxFrameWidth = 1280;

    /** 抽帧最大高度（像素） */
    private int maxFrameHeight = 720;

    /** 单次请求最大帧数（控制上下文长度） */
    private int maxFrames = 16;

    /** 临时文件目录 */
    private String tempDir = "/tmp/videoai";
}
