package com.videoai.common.analysis;

import java.util.function.BiConsumer;

/** 可选调用审计钩子；只传用量与请求 ID，不传凭据、提示词或签名 URL。 */
public final class ExternalUsageReceipt implements AutoCloseable {
    private static final ThreadLocal<BiConsumer<String, String>> CURRENT = new ThreadLocal<>();
    private final BiConsumer<String, String> previous;
    private ExternalUsageReceipt(BiConsumer<String, String> listener) {
        previous = CURRENT.get(); CURRENT.set(listener);
    }
    public static ExternalUsageReceipt listen(BiConsumer<String, String> listener) {
        return new ExternalUsageReceipt(listener);
    }
    public static void report(String usageJson, String requestId) {
        var listener = CURRENT.get();
        if (listener != null) listener.accept(usageJson, requestId);
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
