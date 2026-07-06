package com.videoai.rag.config;

import com.videoai.infra.rag.config.RagProperties;
import com.videoai.infra.rag.vector.VectorStoreClient;
import com.videoai.rag.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * RAG 基础设施健康检查，应用启动时执行。
 *
 * 确保知识库记录存在、Milvus Collection 已就绪。
 * 初始化失败不阻塞启动（fail-open 策略），仅记录 WARN 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagHealthInitializer implements ApplicationRunner {

    private final RagProperties ragProperties;
    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorStoreClient vectorStoreClient;

    @Override
    public void run(ApplicationArguments args) {
        if (!ragProperties.isEnabled()) {
            log.info("RAG is disabled, skipping health check");
            return;
        }

        try {
            knowledgeBaseService.ensureDefaultBase();
            log.info("RAG knowledge base verified: {}", ragProperties.getKnowledgeBase());
        } catch (Exception e) {
            log.warn("RAG knowledge base initialization failed (non-blocking): {}", e.getMessage());
        }

        try {
            vectorStoreClient.ensureCollection();
        } catch (Exception e) {
            log.warn("RAG vector store initialization failed (non-blocking): {}", e.getMessage());
        }
    }
}
