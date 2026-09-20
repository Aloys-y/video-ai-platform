package com.videoai.infra.mysql.mapper;

import com.videoai.common.domain.AiCallLog;
import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface AiCallLogMapper {
    @Insert("""
        INSERT INTO ai_call_log(call_id,task_id,execution_no,stage,subtask_no,model,status,price_snapshot,started_at)
        VALUES(#{callId},#{taskId},#{executionNo},#{stage},#{subtaskNo},#{model},'RUNNING',#{priceSnapshot},#{startedAt})
        """) int insert(AiCallLog row);
    @Select("SELECT * FROM ai_call_log WHERE call_id=#{id}") AiCallLog find(String id);
    @Select("SELECT * FROM ai_call_log WHERE call_id=#{id} FOR UPDATE") AiCallLog lock(String id);
    @Update("UPDATE ai_call_log SET remote_task_id=#{remoteTaskId},request_id=#{requestId} WHERE call_id=#{callId}") int bindRemote(AiCallLog row);
    @Update("""
        UPDATE ai_call_log SET request_id=#{requestId},status=#{status},input_tokens=#{inputTokens},
          output_tokens=#{outputTokens},audio_seconds=#{audioSeconds},usage_json=#{usageJson},
          estimated_cost_cny=#{estimatedCostCny},cost_unknown_reason=#{costUnknownReason},
          error_code=#{errorCode},finished_at=#{finishedAt} WHERE call_id=#{callId}
        """) int finish(AiCallLog row);
    @Select("""
        SELECT * FROM ai_call_log WHERE task_id=#{taskId} AND execution_no=#{executionNo}
          AND stage='ASR' AND subtask_no=#{partNo} AND remote_task_id=#{remoteTaskId}
        """) List<AiCallLog> findAsr(@Param("taskId") String taskId, @Param("executionNo") int executionNo,
                                     @Param("partNo") int partNo, @Param("remoteTaskId") String remoteTaskId);
    @Select("""
        SELECT * FROM ai_call_log WHERE task_id=#{taskId} AND execution_no<=#{executionNo}
          AND stage='ASR' AND subtask_no=#{partNo} AND remote_task_id=#{remoteTaskId}
        """) List<AiCallLog> findAsrOrigin(@Param("taskId") String taskId, @Param("executionNo") int executionNo,
                                           @Param("partNo") int partNo, @Param("remoteTaskId") String remoteTaskId);
    @Select("""
        SELECT execution_no,stage,subtask_no,COUNT(*) AS call_count,
          COALESCE(SUM(estimated_cost_cny),0) AS known_cost_cny,
          SUM(CASE WHEN estimated_cost_cny IS NULL THEN 1 ELSE 0 END) AS incomplete_count,
          SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END) AS running_count,
          SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,SUM(audio_seconds) AS audio_seconds
        FROM ai_call_log WHERE task_id=#{taskId} GROUP BY execution_no,stage,subtask_no
        ORDER BY execution_no,stage,subtask_no
        """) List<Aggregate> summarize(String taskId);

    record Aggregate(int executionNo,String stage,int subtaskNo,long callCount,java.math.BigDecimal knownCostCny,
                     long incompleteCount,long runningCount,java.math.BigDecimal inputTokens,
                     java.math.BigDecimal outputTokens,java.math.BigDecimal audioSeconds) {}
    record Coverage(int executionNo,int ledgerVersion) {}
    record SegmentInfo(int segmentNo,long startMs,long endMs,Integer reusedExecutionNo,Integer reusedSegmentNo) {}

    @Select("""
        SELECT execution_no,CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(config_snapshot,'$.costLedgerVersion'))='1'
          THEN 1 ELSE 0 END AS ledger_version FROM analysis_execution WHERE task_id=#{taskId}
        ORDER BY execution_no
        """) List<Coverage> coverage(String taskId);
    @Select("""
        SELECT segment_no,start_ms,end_ms,reused_execution_no,reused_segment_no FROM analysis_segment
        WHERE task_id=#{taskId} AND execution_no=#{executionNo} ORDER BY segment_no
        """) List<SegmentInfo> segmentInfo(@Param("taskId") String taskId,@Param("executionNo") int executionNo);

}
