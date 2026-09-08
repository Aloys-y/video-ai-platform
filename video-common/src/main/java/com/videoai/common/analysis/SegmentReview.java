package com.videoai.common.analysis;

import java.util.List;

/** 视频模型结构化结果；事件证据已经映射到原视频毫秒坐标。 */
public record SegmentReview(int segmentNo,long startMs,long endMs,String summary,List<Event> events,List<String> uncertainties,List<Advice> advice) {
    public SegmentReview(int segmentNo,long startMs,long endMs,String summary,List<Event> events,List<String> uncertainties) {
        this(segmentNo,startMs,endMs,summary,events,uncertainties,List.of());
    }
    public record Advice(long startMs,long endMs,String issue,String suggestion,String knowledgeBasis) {
        public Advice {
            ContractChecks.range(startMs,endMs); ContractChecks.text(issue,"问题"); ContractChecks.text(suggestion,"建议");
            knowledgeBasis = knowledgeBasis == null ? "" : knowledgeBasis;
        }
    }
    public record Event(long startMs,long endMs,String observation) {
        public Event { ContractChecks.range(startMs,endMs);ContractChecks.text(observation,"画面观察"); }
    }
    public SegmentReview {
        ContractChecks.range(startMs,endMs);ContractChecks.text(summary,"片段摘要");
        if(segmentNo<0) throw new IllegalArgumentException("片段序号无效");
        events=List.copyOf(events);uncertainties=List.copyOf(uncertainties);
        advice=advice == null ? List.of() : List.copyOf(advice);
        for(var e:events) if(e.startMs()<startMs || e.endMs()>endMs) throw new IllegalArgumentException("证据超出片段边界");
        for(var a:advice) if(a.startMs()<startMs || a.endMs()>endMs) throw new IllegalArgumentException("建议证据超出片段边界");
    }
}
