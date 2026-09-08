package com.videoai.infra.mysql.mapper;

import com.videoai.common.domain.AnalysisTextCall;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AnalysisTextCallMapper {
    @Select("SELECT * FROM analysis_text_call WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND purpose=#{purpose} AND batch_no=#{batchNo}")
    AnalysisTextCall select(AnalysisTextCall key);

    @Insert("""
            INSERT INTO analysis_text_call (task_id,execution_no,purpose,batch_no,request_hash)
            SELECT e.task_id,e.execution_no,#{purpose},#{batchNo},#{requestHash} FROM analysis_execution e
            JOIN analysis_task t ON t.task_id=e.task_id WHERE e.task_id=#{taskId} AND e.execution_no=#{executionNo}
              AND t.retry_count=e.execution_no AND t.status='PROCESSING'
            """)
    int claim(AnalysisTextCall call);

    /** 保存已经收到的响应，即使此刻父任务取消也保留审计信息，不触发父任务完成。 */
    @Update("""
            UPDATE analysis_text_call SET response_object_key=#{responseObjectKey}
            WHERE task_id=#{taskId} AND execution_no=#{executionNo} AND purpose=#{purpose} AND batch_no=#{batchNo}
              AND request_hash=#{requestHash} AND response_object_key IS NULL
            """)
    int recordResponse(AnalysisTextCall call);
}
