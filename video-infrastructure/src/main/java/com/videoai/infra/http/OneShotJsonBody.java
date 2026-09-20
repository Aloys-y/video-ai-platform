package com.videoai.infra.http;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import java.io.IOException;

/** 不允许 HTTP 客户端重放付费请求，包括带 Retry-After: 0 的 503 响应。 */
public final class OneShotJsonBody extends RequestBody {
    private final byte[] bytes;
    public OneShotJsonBody(byte[] bytes) { this.bytes=bytes.clone(); }
    @Override public MediaType contentType() { return MediaType.parse("application/json"); }
    @Override public long contentLength() { return bytes.length; }
    @Override public boolean isOneShot() { return true; }
    @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(bytes); }
}
