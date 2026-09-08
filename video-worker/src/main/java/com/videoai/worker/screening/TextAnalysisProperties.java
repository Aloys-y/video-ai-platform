package com.videoai.worker.screening;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.math.BigDecimal;
import java.util.*;

@Data
@Configuration
@ConfigurationProperties(prefix = "analysis.text")
public class TextAnalysisProperties {
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen3-vl-flash";
    @lombok.ToString.Exclude private String apiKey;
    private int timeoutSeconds = 120;
    private int batchSize = 80;
    private int overlap = 10;
    private int maxRequestBytes = 30000;
    private int maxOutputTokens = 4096;
    private int maxBatches = 64;
    private int maxCandidates = 500;
    private long mergeGapMs = 5000;
    private long beforeMs = 10000;
    private long afterMs = 15000;
    private BigDecimal inputCnyPerMillion = new BigDecimal("0.15");
    private BigDecimal outputCnyPerMillion = new BigDecimal("1.5");
    private BigDecimal asrCnyPerSecond = new BigDecimal("0.00022");
    /** 工程预算预留，不是厂商视频报价；P4 接入具体视频模型后校准。 */
    private BigDecimal videoReserveCnyPerMinute = new BigDecimal("0.20");
    private BigDecimal maxEstimatedTaskCny = new BigDecimal("10");

    public void validate() {
        if (batchSize < 1 || overlap < 0 || overlap >= batchSize || maxRequestBytes < 2048 || maxRequestBytes > 30000
                || maxOutputTokens < 1 || maxOutputTokens > 4096 || maxBatches < 1 || maxCandidates < 1
                || beforeMs < 0 || beforeMs > 60000 || afterMs < 0 || afterMs > 60000 || mergeGapMs < 0 || mergeGapMs > 60000
                || timeoutSeconds < 1 || model == null || model.isBlank()) throw new IllegalArgumentException("文本筛选配置无效");
        for (BigDecimal value : List.of(inputCnyPerMillion, outputCnyPerMillion, asrCnyPerSecond, videoReserveCnyPerMinute, maxEstimatedTaskCny))
            if (value.signum() <= 0) throw new IllegalArgumentException("费用预算和单价预留必须大于0");
    }
    public Map<String, Object> snapshot() {
        validate();
        Map<String, Object> result = new TreeMap<>();
        result.put("version", "p3-v1"); result.put("model", model); result.put("baseUrl", baseUrl);
        result.put("screenPrompt", TextPrompts.SCREEN); result.put("summaryPrompt", TextPrompts.SUMMARY);
        result.put("batchSize", batchSize); result.put("overlap", overlap); result.put("maxRequestBytes", maxRequestBytes);
        result.put("maxOutputTokens", maxOutputTokens); result.put("maxBatches", maxBatches); result.put("maxCandidates", maxCandidates);
        result.put("mergeGapMs", mergeGapMs); result.put("beforeMs", beforeMs); result.put("afterMs", afterMs);
        result.put("inputCnyPerMillion", inputCnyPerMillion.toPlainString()); result.put("outputCnyPerMillion", outputCnyPerMillion.toPlainString());
        result.put("asrCnyPerSecond", asrCnyPerSecond.toPlainString()); result.put("videoReserveCnyPerMinute", videoReserveCnyPerMinute.toPlainString());
        result.put("maxEstimatedTaskCny", maxEstimatedTaskCny.toPlainString()); return result;
    }
}
