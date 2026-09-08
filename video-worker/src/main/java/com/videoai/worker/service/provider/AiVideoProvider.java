package com.videoai.worker.service.provider;

/**
 * AI视频分析Provider接口
 *
 * 解耦底层大模型厂商，支持 Zhipu / DashScope 等自由切换
 * 通过 ai.provider 配置项选择激活哪个实现
 */
public interface AiVideoProvider {

    /** 片段路径必须显式支持可审计响应；旧整片接口保持兼容。 */
    record DetailedResult(String text, String usageJson, String requestId, String finishReason) {}

    default DetailedResult callDetailed(String videoUrl, String prompt) throws AiProviderException {
        throw new AiProviderException("该 Provider 尚未验证片段并发与用量接口", false);
    }

    default java.util.Map<String, Object> segmentSettings() {
        return java.util.Map.of("provider", getName(), "supported", false);
    }

    /**
     * 执行一次视频分析API调用
     *
     * @param videoUrl 视频公网URL
     * @param prompt   完整提示词（含系统指令 + 用户提示词）
     * @return AI返回的分析结果
     * @throws AiProviderException API调用异常
     */
    String call(String videoUrl, String prompt) throws AiProviderException;

    /**
     * 获取MinIO预签名URL过期时间（小时）
     */
    int getPresignedUrlExpireHours();

    /**
     * 获取Provider名称（用于日志）
     */
    String getName();
}
