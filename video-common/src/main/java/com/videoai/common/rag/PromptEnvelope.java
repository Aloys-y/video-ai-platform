package com.videoai.common.rag;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class PromptEnvelope {

    private String systemPrompt;
    private String retrievalContext;
    private String userPrompt;
    private Map<String, Object> retrievalSnapshot;

    public String buildFullPrompt() {
        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append(systemPrompt.trim()).append("\n\n");
        }
        if (retrievalContext != null && !retrievalContext.isBlank()) {
            sb.append(retrievalContext.trim()).append("\n\n");
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append(userPrompt.trim());
        }
        return sb.toString().trim();
    }
}
