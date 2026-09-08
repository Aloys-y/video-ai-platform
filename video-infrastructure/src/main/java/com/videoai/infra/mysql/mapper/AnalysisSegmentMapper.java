package com.videoai.infra.mysql.mapper;

import com.videoai.common.domain.AnalysisSegment;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 数据记录用途，没有领取队列或扫描重派接口。唯一键冲突应读取原记录核对，不能覆盖。 */
@Mapper
public interface AnalysisSegmentMapper {
    /** 父任务明确失败时收尾未完成片段；保留成功结果和原始响应引用。 */
    @Update("""
            UPDATE analysis_segment SET status='FAILED',
                error_message='父任务已停止，未完成片段需核对后重试', completed_at=NOW(3)
            WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND status IN ('PREPARED','PROCESSING')
              AND EXISTS (SELECT 1 FROM analysis_task t WHERE t.task_id=analysis_segment.task_id
                AND t.retry_count=analysis_segment.execution_no AND t.status IN ('FAILED','PARTIALLY_COMPLETED'))
            """)
    @Options(timeout = 5)
    int stopUnfinished(@Param("taskId") String taskId, @Param("executionNo") int executionNo);

    @Insert("""
            INSERT INTO analysis_segment (task_id, execution_no, segment_no, start_ms, end_ms, object_key, status)
            SELECT e.task_id, e.execution_no, #{segmentNo}, #{startMs}, #{endMs}, #{objectKey}, 'PREPARED'
            FROM analysis_execution e JOIN analysis_task t ON t.task_id = e.task_id
            WHERE e.task_id = #{taskId} AND e.execution_no = #{executionNo}
              AND t.retry_count = e.execution_no AND t.status = 'PROCESSING'
            """)
    int insertPrepared(AnalysisSegment segment);

    @Select("SELECT * FROM analysis_segment WHERE task_id = #{taskId} AND execution_no = #{executionNo} ORDER BY segment_no")
    List<AnalysisSegment> selectExecution(@Param("taskId") String taskId, @Param("executionNo") int executionNo);

    @Update("""
            UPDATE analysis_segment SET status = 'PROCESSING'
            WHERE task_id = #{taskId} AND execution_no = #{executionNo} AND segment_no = #{segmentNo}
              AND status = 'PREPARED'
              AND EXISTS (SELECT 1 FROM analysis_task t WHERE t.task_id = analysis_segment.task_id
                AND t.retry_count = analysis_segment.execution_no AND t.status = 'PROCESSING')
            """)
    @Options(timeout = 5)
    int markProcessing(@Param("taskId") String taskId, @Param("executionNo") int executionNo,
                       @Param("segmentNo") int segmentNo);

    @Update("""
            UPDATE analysis_segment SET status = #{status}, result = #{result}, error_message = #{errorMessage},
                usage_json = #{usageJson}, completed_at = NOW(3)
            WHERE task_id = #{taskId} AND execution_no = #{executionNo} AND segment_no = #{segmentNo}
              AND status = 'PROCESSING' AND #{status} IN ('SUCCEEDED', 'FAILED')
              AND EXISTS (SELECT 1 FROM analysis_task t WHERE t.task_id = analysis_segment.task_id
                AND t.retry_count = analysis_segment.execution_no AND t.status = 'PROCESSING')
            """)
    @Options(timeout = 5)
    int finish(AnalysisSegment segment);

    /** 先保存响应引用/实际用量，再解析；同代次恢复不重新发起付费调用。 */
    @Update("""
            UPDATE analysis_segment SET usage_json = #{usageJson}
            WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND segment_no=#{segmentNo}
              AND status='PROCESSING' AND usage_json IS NULL
              AND EXISTS (SELECT 1 FROM analysis_task t WHERE t.task_id=analysis_segment.task_id
                AND t.retry_count=analysis_segment.execution_no AND t.status='PROCESSING')
            """)
    @Options(timeout = 5)
    int recordResponse(AnalysisSegment segment);

    @Select("""
            SELECT s.* FROM analysis_segment s
            JOIN analysis_execution old ON old.task_id=s.task_id AND old.execution_no=s.execution_no
            JOIN analysis_execution current ON current.task_id=s.task_id AND current.execution_no=#{executionNo}
            WHERE s.task_id=#{taskId} AND s.execution_no < current.execution_no AND s.status='SUCCEEDED'
              AND old.input_hash=current.input_hash AND old.config_hash=current.config_hash
              AND old.config_snapshot=current.config_snapshot AND old.analysis_mode=current.analysis_mode
              AND s.start_ms=#{startMs} AND s.end_ms=#{endMs} AND s.object_key=#{objectKey}
            ORDER BY s.execution_no DESC LIMIT 1
            """)
    AnalysisSegment selectReusable(AnalysisSegment target);

    /** 同任务、相同输入/完整配置和区间才允许显式复用；新行用量为空，沿来源查询旧用量。 */
    @Insert("""
            INSERT INTO analysis_segment
              (task_id, execution_no, segment_no, start_ms, end_ms, object_key, status, result,
               reused_execution_no, reused_segment_no, completed_at)
            SELECT s.task_id, e.execution_no, #{segmentNo}, s.start_ms, s.end_ms, s.object_key,
              'SUCCEEDED', s.result, s.execution_no, s.segment_no, NOW(3)
            FROM analysis_segment s
            JOIN analysis_execution old ON old.task_id = s.task_id AND old.execution_no = s.execution_no
            JOIN analysis_execution e ON e.task_id = s.task_id AND e.execution_no = #{executionNo}
            JOIN analysis_task t ON t.task_id = e.task_id AND t.retry_count = e.execution_no
            WHERE s.task_id = #{taskId} AND s.execution_no = #{sourceExecutionNo}
              AND s.segment_no = #{sourceSegmentNo} AND s.status = 'SUCCEEDED'
              AND s.execution_no < e.execution_no AND t.status = 'PROCESSING'
              AND old.input_hash = e.input_hash AND old.config_hash = e.config_hash
              AND old.config_snapshot = e.config_snapshot AND old.analysis_mode = e.analysis_mode
              AND s.start_ms = #{startMs} AND s.end_ms = #{endMs}
            """)
    int reuseSucceeded(@Param("taskId") String taskId, @Param("executionNo") int executionNo,
                       @Param("segmentNo") int segmentNo, @Param("sourceExecutionNo") int sourceExecutionNo,
                       @Param("sourceSegmentNo") int sourceSegmentNo,
                       @Param("startMs") long startMs, @Param("endMs") long endMs);
}
