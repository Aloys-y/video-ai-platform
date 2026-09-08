package com.videoai.common.analysis;

final class ContractChecks {
    private ContractChecks() {}

    static void text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }

    static void range(long startMs, long endMs) {
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("无效的原视频毫秒区间");
    }

    static void objectKey(String value) {
        text(value, "objectKey");
        if (value.contains("://") || value.contains("?")) {
            throw new IllegalArgumentException("必须保存对象键，不能保存签名 URL");
        }
    }
}
