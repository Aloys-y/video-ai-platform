package com.videoai.worker.service.provider;

/**
 * AI视频分析Provider接口
 *
 * 解耦底层大模型厂商，支持 Zhipu / DashScope 等自由切换
 * 通过 ai.provider 配置项选择激活哪个实现
 */
public interface AiVideoProvider {

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
