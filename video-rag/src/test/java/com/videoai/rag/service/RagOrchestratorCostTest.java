package com.videoai.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.AiCallContext;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.rag.RagContext;
import com.videoai.infra.mysql.mapper.TaskRagContextMapper;
import com.videoai.infra.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class RagOrchestratorCostTest {
    @Test void videoExecutionCreatesExplicitQueryContext() {
        var props=new RagProperties();props.setEnabled(true);
        var retrieval=mock(KnowledgeRetrievalService.class);var prompts=mock(ApexPromptTemplateService.class);
        var task=new AnalysisTask();task.setTaskId("video");task.setPrompt("question");
        var context=new AiCallContext("video",4,AiCallContext.Stage.RAG_EMBEDDING,0);
        when(prompts.normalizeUserPrompt("question")).thenReturn("normalized");
        when(retrieval.retrieve(context,"normalized")).thenReturn(RagContext.builder().status("MISS").hits(List.of()).contextText("").build());
        new RagOrchestrator(props,retrieval,prompts,mock(TaskRagContextMapper.class),new ObjectMapper()).buildPrompt(task,4);
        verify(retrieval).retrieve(context,"normalized");verify(retrieval,never()).retrieve(anyString());
    }
}
