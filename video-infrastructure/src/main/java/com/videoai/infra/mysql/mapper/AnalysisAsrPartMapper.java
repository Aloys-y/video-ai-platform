package com.videoai.infra.mysql.mapper;

import com.videoai.common.domain.AnalysisAsrPart;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AnalysisAsrPartMapper {
    @Select("SELECT * FROM analysis_asr_part WHERE task_id = #{taskId} AND execution_no = #{executionNo} ORDER BY part_no")
    List<AnalysisAsrPart> selectExecution(@Param("taskId") String taskId, @Param("executionNo") int executionNo);

    @Insert("""
            INSERT INTO analysis_asr_part (task_id, execution_no, part_no, start_ms, end_ms, audio_object_key,
                asr_task_id, transcript_object_key, usage_json, reused_execution_no)
            SELECT e.task_id, e.execution_no, #{partNo}, #{startMs}, #{endMs}, #{audioObjectKey},
                #{asrTaskId}, #{transcriptObjectKey}, #{usageJson}, #{reusedExecutionNo}
            FROM analysis_execution e JOIN analysis_task t ON t.task_id = e.task_id
            WHERE e.task_id = #{taskId} AND e.execution_no = #{executionNo}
              AND t.attempt_no = e.execution_no AND t.status='RUNNING'
            """)
    int insert(AnalysisAsrPart part);

    @Select("""
            SELECT p.* FROM analysis_asr_part p WHERE p.task_id = #{taskId}
              AND p.execution_no = (SELECT MAX(e.execution_no) FROM analysis_execution e
                WHERE e.task_id = #{taskId} AND e.execution_no < #{executionNo}
                  AND e.input_hash = #{inputHash} AND e.config_snapshot = #{configSnapshot}
                  AND EXISTS (SELECT 1 FROM analysis_asr_part a WHERE a.task_id = e.task_id AND a.execution_no = e.execution_no))
            ORDER BY p.part_no
            """)
    List<AnalysisAsrPart> selectReusablePlan(@Param("taskId") String taskId, @Param("executionNo") int executionNo,
                                           @Param("inputHash") String inputHash, @Param("configSnapshot") String configSnapshot);

    @Update("""
            UPDATE analysis_asr_part SET asr_task_id = 'SUBMITTING'
            WHERE task_id = #{taskId} AND execution_no = #{executionNo} AND part_no = #{partNo} AND asr_task_id IS NULL
              AND EXISTS (SELECT 1 FROM analysis_task t WHERE t.task_id = analysis_asr_part.task_id
                AND t.attempt_no = analysis_asr_part.execution_no AND t.status='RUNNING')
            """)
    int claimSubmission(AnalysisAsrPart part);

    /** 已提交的远端ID即使父任务刚取消也要记录，用于审计和避免重复费用，不会驱动父任务。 */
    @Update("""
            UPDATE analysis_asr_part SET asr_task_id = #{asrTaskId}
            WHERE task_id = #{taskId} AND execution_no = #{executionNo} AND part_no = #{partNo}
              AND asr_task_id = 'SUBMITTING' AND #{asrTaskId} <> 'SUBMITTING'
            """)
    int recordSubmitted(AnalysisAsrPart part);

    @Update("""
            UPDATE analysis_asr_part SET transcript_object_key = #{transcriptObjectKey}, usage_json = #{usageJson}
            WHERE task_id = #{taskId} AND execution_no = #{executionNo} AND part_no = #{partNo}
              AND asr_task_id = #{asrTaskId} AND asr_task_id <> 'SUBMITTING' AND transcript_object_key IS NULL
              AND EXISTS (SELECT 1 FROM analysis_task t WHERE t.task_id = analysis_asr_part.task_id
                AND t.attempt_no = analysis_asr_part.execution_no AND t.status='RUNNING')
            """)
    int recordResult(AnalysisAsrPart part);
}
