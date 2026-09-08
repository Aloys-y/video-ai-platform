package com.videoai.worker.segment;

import com.videoai.common.analysis.PreparedSegment;
import com.videoai.common.domain.AnalysisSegment;
import com.videoai.infra.mysql.mapper.AnalysisSegmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 独立短事务；不传递父事务到模型线程。 */
@Service
@RequiredArgsConstructor
public class SegmentPersistenceService {
    private final AnalysisSegmentMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<AnalysisSegment> prepare(String taskId, int no, List<PreparedSegment> plan) {
        Map<Integer, AnalysisSegment> existing = new HashMap<>();
        for (var row : mapper.selectExecution(taskId, no)) existing.put(row.getSegmentNo(), row);
        Set<Integer> ids = new HashSet<>();
        for (var segment : plan) {
            if (!ids.add(segment.segmentNo())) throw new IllegalStateException("冻结清单片段编号重复");
            var row = existing.get(segment.segmentNo());
            if (row == null) {
                row = row(taskId, no, segment);
                var source = mapper.selectReusable(row);
                int inserted = source == null ? mapper.insertPrepared(row) : mapper.reuseSucceeded(taskId, no,
                        segment.segmentNo(), source.getExecutionNo(), source.getSegmentNo(), segment.startMs(), segment.endMs());
                if (inserted != 1) throw new IllegalStateException("片段准备或复用写入被拒绝");
            } else if (row.getStartMs() != segment.startMs() || row.getEndMs() != segment.endMs()
                    || !Objects.equals(row.getObjectKey(), segment.objectKey())) {
                throw new IllegalStateException("数据库片段与冻结清单不一致");
            }
        }
        if (!ids.containsAll(existing.keySet())) throw new IllegalStateException("数据库存在清单外片段");
        return mapper.selectExecution(taskId, no);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void start(AnalysisSegment row) {
        if (mapper.markProcessing(row.getTaskId(), row.getExecutionNo(), row.getSegmentNo()) != 1)
            throw new IllegalStateException("片段已启动或执行失效，禁止重复调用");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void response(AnalysisSegment row) {
        if (mapper.recordResponse(row) != 1) throw new IllegalStateException("片段响应写入被拒绝");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void finish(AnalysisSegment row) {
        if (mapper.finish(row) != 1) throw new IllegalStateException("片段终态写入被拒绝");
    }

    static AnalysisSegment row(String taskId, int no, PreparedSegment s) {
        var row = new AnalysisSegment(); row.setTaskId(taskId); row.setExecutionNo(no); row.setSegmentNo(s.segmentNo());
        row.setStartMs(s.startMs()); row.setEndMs(s.endMs()); row.setObjectKey(s.objectKey()); row.setStatus("PREPARED");
        return row;
    }
}
