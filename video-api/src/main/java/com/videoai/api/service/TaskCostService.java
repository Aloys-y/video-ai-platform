package com.videoai.api.service;

import com.videoai.common.analysis.AiCallContext.Stage;
import com.videoai.infra.mysql.mapper.AiCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** 一致性快照内先校验归属，再只聚合账本；业务表只提供覆盖标记和片段元数据。 */
@Service
@RequiredArgsConstructor
public class TaskCostService {
    private final TaskSegmentService ownership;
    private final AiCallLogMapper ledger;
    public record Totals(String knownCostCny,long callCount,long incompleteCount,long runningCount,
                         String inputTokens,String outputTokens,String audioSeconds,boolean coverageKnown,boolean complete) {}
    public record StageCost(String stage,Totals current,Totals lifetime) {}
    public record SegmentCost(int segmentNo,Long startMs,Long endMs,boolean reused,Totals current,Totals reusedSource) {}
    public record Result(int executionNo,String taskStatus,Totals current,Totals lifetime,
                         List<StageCost> stages,List<SegmentCost> segments) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result get(String taskId,Long userId) {
        var task=ownership.ownedTask(taskId,userId);
        int no=task.getAttemptNo()==null?0:task.getAttemptNo();
        boolean terminal=Set.of("SUCCEEDED","PARTIAL","FAILED","CANCELLED").contains(task.getStatus());
        var coverage=ledger.coverage(taskId).stream().filter(c->c.executionNo()<=no).toList();
        boolean currentCovered=coverage.stream().anyMatch(c->c.executionNo()==no && c.ledgerVersion()==1);
        boolean allCovered=currentCovered && coverage.stream().allMatch(c->c.ledgerVersion()==1);
        var groups=ledger.summarize(taskId).stream().filter(g->g.executionNo()<=no).toList();
        var current=new Sum();var lifetime=new Sum();
        var stageCurrent=new EnumMap<Stage,Sum>(Stage.class);var stageAll=new EnumMap<Stage,Sum>(Stage.class);
        var segmentSums=new TreeMap<Integer,Sum>();
        for(var group:groups) {
            var stage=Stage.valueOf(group.stage());
            lifetime.add(group);stageAll.computeIfAbsent(stage,k->new Sum()).add(group);
            if(group.executionNo()==no) {
                current.add(group);stageCurrent.computeIfAbsent(stage,k->new Sum()).add(group);
                if(stage==Stage.VIDEO_ANALYSIS)segmentSums.computeIfAbsent(group.subtaskNo(),k->new Sum()).add(group);
            }
        }
        var stages=new ArrayList<StageCost>();
        for(var stage:Stage.values())stages.add(new StageCost(stage.name(),
                stageCurrent.getOrDefault(stage,new Sum()).view(currentCovered,terminal),
                stageAll.getOrDefault(stage,new Sum()).view(allCovered,terminal)));
        var metadata=new TreeMap<Integer,AiCallLogMapper.SegmentInfo>();
        for(var row:ledger.segmentInfo(taskId,no)){metadata.put(row.segmentNo(),row);segmentSums.computeIfAbsent(row.segmentNo(),k->new Sum());}
        var segments=new ArrayList<SegmentCost>();
        var historyMetadata=new HashMap<Integer,List<AiCallLogMapper.SegmentInfo>>();
        segmentSums.forEach((index,sum)->{
            var info=metadata.get(index);
            Totals sourceCost=null;
            if(info!=null && info.reusedExecutionNo()!=null) {
                Integer sourceNo=info.reusedExecutionNo(), sourceIndex=info.reusedSegmentNo();
                int upper=no;
                while(sourceNo!=null && sourceIndex!=null && sourceNo<upper) {
                    int execution=sourceNo, segment=sourceIndex;
                    var sourceSum=new Sum();
                    groups.stream().filter(g->g.executionNo()==execution && g.subtaskNo()==segment
                            && g.stage().equals(Stage.VIDEO_ANALYSIS.name())).forEach(sourceSum::add);
                    boolean covered=coverage.stream().anyMatch(c->c.executionNo()==execution && c.ledgerVersion()==1);
                    sourceCost=sourceSum.view(covered && sourceSum.calls>0,true);
                    if(sourceSum.calls>0) break;
                    var source=historyMetadata.computeIfAbsent(execution,k->ledger.segmentInfo(taskId,k)).stream()
                            .filter(r->r.segmentNo()==segment).findFirst().orElse(null);
                    if(source==null) break;
                    upper=execution;sourceNo=source.reusedExecutionNo();sourceIndex=source.reusedSegmentNo();
                }
            }
            segments.add(new SegmentCost(index,info==null?null:info.startMs(),info==null?null:info.endMs(),
                    info!=null && info.reusedExecutionNo()!=null,sum.view(currentCovered,terminal),sourceCost));
        });
        return new Result(no,task.getStatus(),current.view(currentCovered,terminal),lifetime.view(allCovered,terminal),
                List.copyOf(stages),List.copyOf(segments));
    }
    private static final class Sum {
        BigDecimal cost=BigDecimal.ZERO,input,output,seconds;
        long calls,unknown,running;
        void add(AiCallLogMapper.Aggregate row) {
            cost=cost.add(row.knownCostCny());calls+=row.callCount();unknown+=row.incompleteCount();running+=row.runningCount();
            input=addNullable(input,row.inputTokens());output=addNullable(output,row.outputTokens());seconds=addNullable(seconds,row.audioSeconds());
        }
        Totals view(boolean covered,boolean terminal) {
            return new Totals(cost.setScale(10).toPlainString(),calls,unknown,running,plain(input),plain(output),plain(seconds),
                    covered,covered && unknown==0 && running==0 && terminal);
        }
        static BigDecimal addNullable(BigDecimal a,BigDecimal b){return b==null?a:a==null?b:a.add(b);}
        static String plain(BigDecimal value){return value==null?null:value.toPlainString();}
    }
}
