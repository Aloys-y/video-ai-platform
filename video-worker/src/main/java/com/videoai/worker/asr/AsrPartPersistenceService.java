package com.videoai.worker.asr;

import com.videoai.common.domain.AnalysisAsrPart;
import com.videoai.infra.mysql.mapper.AnalysisAsrPartMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AsrPartPersistenceService {
    private final AnalysisAsrPartMapper mapper;

    /** 所有音轨上传后一次保存完整清单，避免恢复时误认为只有前半段。 */
    @Transactional
    public void savePlan(List<AnalysisAsrPart> parts) {
        for (AnalysisAsrPart part : parts) {
            if (mapper.insert(part) != 1) throw new IllegalStateException("音轨清单保存失败，执行已失效");
        }
    }
}
