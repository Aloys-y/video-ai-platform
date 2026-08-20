package com.videoai.rag.service;

import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.exception.BusinessException;
import com.videoai.infra.mysql.mapper.KnowledgeBaseMapper;
import com.videoai.infra.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RagProperties ragProperties;

    @Transactional
    public KnowledgeBase ensureDefaultBase() {
        KnowledgeBase base = knowledgeBaseMapper.selectByBaseCode(ragProperties.getKnowledgeBase());
        if (base != null) {
            return base;
        }

        base = new KnowledgeBase();
        base.setBaseCode(ragProperties.getKnowledgeBase());
        base.setName("Apex 默认知识库");
        base.setDomain("APEX");
        base.setStatus("ACTIVE");
        base.setCurrentVersionTag("current");
        knowledgeBaseMapper.insert(base);
        return base;
    }

    public KnowledgeBase getRequiredBase() {
        KnowledgeBase base = ensureDefaultBase();
        if (base == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return base;
    }

    @Transactional
    public KnowledgeBase updateCurrentVersion(String versionTag) {
        if (versionTag == null || versionTag.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_VERSION_INVALID);
        }
        KnowledgeBase base = getRequiredBase();
        knowledgeBaseMapper.updateCurrentVersion(base.getBaseCode(), versionTag.trim());
        return knowledgeBaseMapper.selectByBaseCode(base.getBaseCode());
    }
}
