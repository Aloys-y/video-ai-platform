package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.common.domain.KnowledgeChunk;
import com.videoai.common.dto.response.LegendKnowledgeAuditResponse;
import com.videoai.common.enums.KnowledgeIndexStatus;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.rag.config.MilvusProperties;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import com.videoai.infra.rag.vector.VectorStoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegendKnowledgeAuditServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private KnowledgeCardMapper knowledgeCardMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private VectorStoreClient vectorStoreClient;

    @Test
    void shouldAuditCoverageAndExposeOrphanVectors() {
        LegendKnowledgeAuditService service = new LegendKnowledgeAuditService(
                knowledgeBaseService, knowledgeCardMapper, knowledgeChunkMapper, vectorStoreClient,
                milvusProperties(), embeddingProperties());
        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode("apex-default");
        base.setCurrentVersionTag("v1");

        KnowledgeCard indexed = card("ash-mobile", "Ash (Mobile)", 1, KnowledgeIndexStatus.INDEXED.getCode());
        KnowledgeCard incomplete = card("wraith", "Wraith", 1, KnowledgeIndexStatus.INDEXED.getCode());
        when(knowledgeBaseService.getRequiredBase()).thenReturn(base);
        when(knowledgeCardMapper.selectByCategory("apex-default", "LEGEND"))
                .thenReturn(List.of(indexed, incomplete));
        when(knowledgeChunkMapper.selectByCardCode("apex-default", "ash-mobile"))
                .thenReturn(List.of(new KnowledgeChunk(), new KnowledgeChunk()));
        when(knowledgeChunkMapper.selectByCardCode("apex-default", "wraith"))
                .thenReturn(List.of(new KnowledgeChunk(), new KnowledgeChunk()));
        when(vectorStoreClient.countByCard(
                "kb_code == \"apex-default\" and category == \"LEGEND\""))
                .thenReturn(Map.of("ash-mobile", 2L, "wraith", 1L, "deleted-card", 3L));

        LegendKnowledgeAuditResponse result = service.audit();

        assertEquals(2, result.getTotalCards());
        assertEquals(1, result.getMatchedCards());
        assertEquals(2, result.getMismatchCards());
        assertEquals(6L, result.getMilvusVectorCount());
        assertEquals(1, result.getOrphanVectorCards());
        assertEquals(3L, result.getOrphanVectorCount());
        assertEquals("apex_knowledge_chunk", result.getCollectionName());
        assertEquals("text-embedding-v3", result.getEmbeddingModel());
        assertEquals(1024, result.getEmbeddingDimension());
        assertEquals("MOBILE", result.getCards().get(0).getPlatform());
        assertEquals("MATCHED_INDEXED", result.getCards().get(0).getCoverageStatus());
        assertEquals("MILVUS_MISSING", result.getCards().get(1).getCoverageStatus());
    }

    private MilvusProperties milvusProperties() {
        MilvusProperties properties = new MilvusProperties();
        properties.setCollection("apex_knowledge_chunk");
        return properties;
    }

    private OpenAiEmbeddingProperties embeddingProperties() {
        OpenAiEmbeddingProperties properties = new OpenAiEmbeddingProperties();
        properties.setProvider("dashscope");
        properties.setModel("text-embedding-v3");
        properties.setDimension(1024);
        properties.getDashscope().setTextType("document");
        return properties;
    }

    private KnowledgeCard card(String code, String title, int enabled, String indexStatus) {
        KnowledgeCard card = new KnowledgeCard();
        card.setCardCode(code);
        card.setTitle(title);
        card.setContentMarkdown("content for " + code);
        card.setEnabled(enabled);
        card.setIndexStatus(indexStatus);
        card.setVersionTag("v1");
        return card;
    }
}
