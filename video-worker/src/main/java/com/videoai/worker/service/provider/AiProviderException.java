package com.videoai.worker.service.provider;

/**
 * AI Provider调用异常
 */
public class AiProviderException extends Exception {

    private final boolean retryable;

    public AiProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public AiProviderException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
