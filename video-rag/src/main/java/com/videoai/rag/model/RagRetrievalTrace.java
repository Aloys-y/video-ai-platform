package com.videoai.rag.model;

import com.videoai.common.rag.RagContext;
import com.videoai.common.rag.RetrievalHit;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 检索调试轨迹。生产链路只消费 context，候选阶段仅供管理员评测接口分析。
 */
@Data
@Builder
public class RagRetrievalTrace {

    private RagContext context;
    private List<RetrievalHit> rawCandidates;
    private List<RetrievalHit> lexicalCandidates;
    private List<RetrievalHit> fusedCandidates;
    private List<RetrievalHit> scorePassedCandidates;
    private List<RetrievalHit> diversifiedCandidates;
    private List<RetrievalHit> selectedCandidates;
}
