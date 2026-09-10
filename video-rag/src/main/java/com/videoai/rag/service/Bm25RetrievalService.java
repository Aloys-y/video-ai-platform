package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeChunk;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.model.LexicalSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面向当前小规模中文英雄语料的轻量 BM25。
 *
 * <p>中文采用连续汉字的二元字词，英文和数字采用完整词；标题重复 3 次、标题路径重复
 * 2 次，以提高英雄名和技能名的精确匹配权重。索引按知识库版本缓存，不在请求中反复查库。</p>
 */
@Service
@RequiredArgsConstructor
public class Bm25RetrievalService {

    private static final double K1 = 1.2D;
    private static final double B = 0.75D;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final RagProperties ragProperties;

    private volatile CorpusSnapshot cachedSnapshot;

    public List<LexicalSearchResult> search(String query, String baseCode, String versionTag, int topK) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || topK <= 0) {
            return List.of();
        }

        CorpusSnapshot corpus = getCorpus(baseCode, versionTag);
        if (corpus.documents().isEmpty()) {
            return List.of();
        }

        Set<String> uniqueQueryTokens = new HashSet<>(queryTokens);
        List<LexicalSearchResult> results = new ArrayList<>();
        for (IndexedDocument document : corpus.documents()) {
            double score = score(document, uniqueQueryTokens, corpus);
            if (score > 0D) {
                results.add(new LexicalSearchResult(document.chunk(), score));
            }
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(LexicalSearchResult::score).reversed()
                        .thenComparing(result -> result.chunk().getVectorId()))
                .limit(topK)
                .toList();
    }

    private CorpusSnapshot getCorpus(String baseCode, String versionTag) {
        long now = System.currentTimeMillis();
        CorpusSnapshot current = cachedSnapshot;
        long ttlMillis = Math.max(1, ragProperties.getLexicalIndexTtlSeconds()) * 1000L;
        if (current != null && current.matches(baseCode, versionTag) && now - current.loadedAtMs() < ttlMillis) {
            return current;
        }

        synchronized (this) {
            current = cachedSnapshot;
            if (current != null && current.matches(baseCode, versionTag)
                    && now - current.loadedAtMs() < ttlMillis) {
                return current;
            }
            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectForRetrieval(baseCode, versionTag);
            cachedSnapshot = buildCorpus(baseCode, versionTag, chunks, now);
            return cachedSnapshot;
        }
    }

    CorpusSnapshot buildCorpus(String baseCode, String versionTag,
                               List<KnowledgeChunk> chunks, long loadedAtMs) {
        List<IndexedDocument> documents = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalLength = 0L;
        for (KnowledgeChunk chunk : chunks) {
            List<String> tokens = weightedTokens(chunk);
            if (tokens.isEmpty()) {
                continue;
            }
            Map<String, Integer> termFrequency = new LinkedHashMap<>();
            for (String token : tokens) {
                termFrequency.merge(token, 1, Integer::sum);
            }
            termFrequency.keySet().forEach(token -> documentFrequency.merge(token, 1, Integer::sum));
            documents.add(new IndexedDocument(chunk, Map.copyOf(termFrequency), tokens.size()));
            totalLength += tokens.size();
        }
        double averageLength = documents.isEmpty() ? 0D : (double) totalLength / documents.size();
        return new CorpusSnapshot(baseCode, versionTag, List.copyOf(documents),
                Map.copyOf(documentFrequency), averageLength, loadedAtMs);
    }

    double score(IndexedDocument document, Set<String> queryTokens, CorpusSnapshot corpus) {
        double score = 0D;
        int documentCount = corpus.documents().size();
        for (String token : queryTokens) {
            int tf = document.termFrequency().getOrDefault(token, 0);
            if (tf == 0) {
                continue;
            }
            int df = corpus.documentFrequency().getOrDefault(token, 0);
            double idf = Math.log(1D + (documentCount - df + 0.5D) / (df + 0.5D));
            double lengthRatio = corpus.averageLength() == 0D
                    ? 1D : document.length() / corpus.averageLength();
            double denominator = tf + K1 * (1D - B + B * lengthRatio);
            score += idf * (tf * (K1 + 1D)) / denominator;
        }
        return score;
    }

    private List<String> weightedTokens(KnowledgeChunk chunk) {
        List<String> tokens = new ArrayList<>();
        append(tokens, tokenize(chunk.getTitle()), 3);
        append(tokens, tokenize(chunk.getHeadingPath()), 2);
        append(tokens, tokenize(chunk.getContentText()), 1);
        return tokens;
    }

    private void append(List<String> target, List<String> source, int repeats) {
        for (int i = 0; i < repeats; i++) {
            target.addAll(source);
        }
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder han = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushLatin(latin, tokens);
                han.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushHan(han, tokens);
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(latin, tokens);
                flushHan(han, tokens);
            }
        });
        flushLatin(latin, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private static void flushLatin(StringBuilder buffer, List<String> tokens) {
        if (!buffer.isEmpty()) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private static void flushHan(StringBuilder buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        int[] codePoints = buffer.codePoints().toArray();
        if (codePoints.length == 1) {
            tokens.add(new String(codePoints, 0, 1));
        } else {
            for (int i = 0; i < codePoints.length - 1; i++) {
                tokens.add(new String(codePoints, i, 2));
            }
        }
        buffer.setLength(0);
    }

    record IndexedDocument(KnowledgeChunk chunk, Map<String, Integer> termFrequency, int length) {
    }

    record CorpusSnapshot(String baseCode, String versionTag, List<IndexedDocument> documents,
                          Map<String, Integer> documentFrequency, double averageLength,
                          long loadedAtMs) {
        boolean matches(String expectedBaseCode, String expectedVersionTag) {
            return java.util.Objects.equals(baseCode, expectedBaseCode)
                    && java.util.Objects.equals(versionTag, expectedVersionTag);
        }
    }
}
