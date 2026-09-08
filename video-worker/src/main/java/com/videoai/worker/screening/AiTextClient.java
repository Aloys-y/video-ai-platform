package com.videoai.worker.screening;

import java.io.IOException;

/** 纯文本调用；返回原始响应，先持久化再做业务解析。 */
public interface AiTextClient {
    String complete(String systemPrompt, String userText) throws IOException;
}
