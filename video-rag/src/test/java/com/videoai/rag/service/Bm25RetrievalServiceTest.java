package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeChunk;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.model.LexicalSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class Bm25RetrievalServiceTest {

    @Test
    void shouldTokenizeChineseBigramsAndLatinTerms() {
        List<String> tokens = Bm25RetrievalService.tokenize("黑洞 EMP R-301");

        assertTrue(tokens.contains("黑洞"));
        assertTrue(tokens.contains("emp"));
        assertTrue(tokens.contains("r"));
        assertTrue(tokens.contains("301"));
    }

    @Test
    void shouldRankExactAbilityChunkAheadOfGenericOverview() {
        Bm25RetrievalService service = new Bm25RetrievalService(
                mock(KnowledgeChunkMapper.class), new RagProperties());
        KnowledgeChunk overview = chunk("horizon_overview", "地平线", "地平线 > 玩法概览",
                "地平线可以利用重力获得制高点并配合队友作战。");
        KnowledgeChunk ultimate = chunk("horizon_black_hole", "地平线", "地平线 > 技能 > 黑洞",
                "黑洞会把附近敌人牵引到一起，适合配合队友集火。");

        Bm25RetrievalService.CorpusSnapshot corpus = service.buildCorpus(
                "apex-default", "current", List.of(overview, ultimate), 1L);
        // 通过与生产 search 相同的 BM25 公式验证精确技能词具有更高分值。
        var queryTokens = new java.util.HashSet<>(Bm25RetrievalService.tokenize("地平线黑洞如何集火"));
        double overviewScore = service.score(corpus.documents().get(0), queryTokens, corpus);
        double ultimateScore = service.score(corpus.documents().get(1), queryTokens, corpus);

        assertTrue(ultimateScore > overviewScore);
    }

    private KnowledgeChunk chunk(String vectorId, String title, String heading, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setVectorId(vectorId);
        chunk.setCardCode(title);
        chunk.setTitle(title);
        chunk.setCategory("LEGEND");
        chunk.setHeadingPath(heading);
        chunk.setContentText(content);
        return chunk;
    }
}
