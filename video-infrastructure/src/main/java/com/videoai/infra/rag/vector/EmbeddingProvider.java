package com.videoai.infra.rag.vector;

import java.util.List;

public interface EmbeddingProvider {

    List<Float> embed(String text);
}
