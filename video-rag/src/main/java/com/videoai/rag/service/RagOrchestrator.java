package com.videoai.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.domain.TaskRagContext;
import com.videoai.common.rag.PromptEnvelope;
import com.videoai.common.rag.RagContext;
import com.videoai.infra.mysql.mapper.TaskRagContextMapper;
import com.videoai.infra.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagOrchestrator {

    private final RagProperties ragProperties;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final ApexPromptTemplateService promptTemplateService;
    private final TaskRagContextMapper taskRagContextMapper;
    private final ObjectMapper objectMapper;

    public PromptEnvelope buildPrompt(AnalysisTask task) {
        String userPrompt = promptTemplateService.normalizeUserPrompt(task.getPrompt());
        if (!ragProperties.isEnabled()) {
            return PromptEnvelope.builder()
                    .systemPrompt(promptTemplateService.systemPrompt())
                    .retrievalContext("")
                    .userPrompt(userPrompt)
                    .retrievalSnapshot(Map.of("status", "DISABLED"))
                    .build();
        }

        try {
            RagContext ragContext = knowledgeRetrievalService.retrieve(userPrompt);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("status", ragContext.getStatus());
            snapshot.put("baseCode", ragContext.getBaseCode());
            snapshot.put("versionTag", ragContext.getVersionTag());
            snapshot.put("queryText", ragContext.getQueryText());
            snapshot.put("latencyMs", ragContext.getLatencyMs());
            snapshot.put("hits", ragContext.getHits());
            snapshot.put("contextText", ragContext.getContextText());

            return PromptEnvelope.builder()
                    .systemPrompt(promptTemplateService.systemPrompt())
                    .retrievalContext(promptTemplateService.retrievalBlock(ragContext.getContextText()))
                    .userPrompt(userPrompt)
                    .retrievalSnapshot(snapshot)
                    .build();
        } catch (Exception e) {
            log.warn("RAG build prompt degraded: {}", e.getMessage());
            if (!ragProperties.isFailOpen()) {
                throw e;
            }
            return PromptEnvelope.builder()
                    .systemPrompt(promptTemplateService.systemPrompt())
                    .retrievalContext("")
                    .userPrompt(userPrompt)
                    .retrievalSnapshot(Map.of("status", "DEGRADED", "error", e.getMessage()))
                    .build();
        }
    }

    public void saveTaskContext(String taskId, PromptEnvelope envelope) {
        Map<String, Object> snapshot = envelope.getRetrievalSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }

        TaskRagContext context = new TaskRagContext();
        context.setTaskId(taskId);
        context.setBaseCode(String.valueOf(snapshot.getOrDefault("baseCode", ragProperties.getKnowledgeBase())));
        context.setVersionTag(String.valueOf(snapshot.getOrDefault("versionTag", "")));
        context.setQueryText(String.valueOf(snapshot.getOrDefault("queryText", "")));
        context.setRetrievalMode("milvus-ann");
        context.setTopK(ragProperties.getTopK());
        context.setHitCount(extractHitCount(snapshot.get("hits")));
        context.setContextChars(envelope.getRetrievalContext() == null ? 0 : envelope.getRetrievalContext().length());
        context.setStatus(String.valueOf(snapshot.getOrDefault("status", "UNKNOWN")));
        context.setLatencyMs((Integer) snapshot.getOrDefault("latencyMs", 0));
        context.setSnapshotJson(writeSnapshot(snapshot));
        taskRagContextMapper.insert(context);
    }

    private int extractHitCount(Object hits) {
        if (hits instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private String writeSnapshot(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize RAG snapshot", e);
        }
    }
}
