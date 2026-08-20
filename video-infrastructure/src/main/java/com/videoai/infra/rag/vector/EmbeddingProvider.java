package com.videoai.infra.rag.vector;

import java.util.List;

public interface EmbeddingProvider {

    List<Float> embed(String text);

    /**
     * 为待索引的知识文档生成向量。默认兼容不区分文本类型的 Embedding 服务。
     */
    default List<Float> embedDocument(String text) {
        return embed(text);
    }

    /**
     * 为在线检索查询生成向量。部分模型会针对 query/document 使用不同的编码提示。
     */
    default List<Float> embedQuery(String text) {
        return embed(text);
    }
}
