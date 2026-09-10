package com.videoai.infra.mysql.mapper;

import com.videoai.common.domain.AnalysisExecution;
import org.apache.ibatis.annotations.*;

/** 不提供通用 update/delete，避免无意覆盖历史快照。写入返回 0 时调用方必须停止当前执行。 */
@Mapper
public interface AnalysisExecutionMapper {
    @Insert("""
            INSERT INTO analysis_execution
              (task_id, execution_no, analysis_mode, config_snapshot, config_hash, input_hash)
            SELECT task_id, attempt_no, analysis_mode, #{configSnapshot}, #{configHash}, #{inputHash}
            FROM analysis_task WHERE task_id = #{taskId} AND attempt_no = #{executionNo}
              AND status = 'RUNNING' AND analysis_mode = #{analysisMode}
            """)
    int insert(AnalysisExecution execution);

    @Select("SELECT * FROM analysis_execution WHERE task_id = #{taskId} AND execution_no = #{executionNo}")
    AnalysisExecution selectExecution(@Param("taskId") String taskId, @Param("executionNo") int executionNo);

    @Select("""
            SELECT old.* FROM analysis_execution old JOIN analysis_execution current
              ON current.task_id=old.task_id AND current.execution_no=#{executionNo}
            WHERE old.task_id=#{taskId} AND old.execution_no < current.execution_no
              AND old.input_hash=current.input_hash AND old.config_hash=current.config_hash
              AND old.config_snapshot=current.config_snapshot AND old.analysis_mode=current.analysis_mode
              AND old.transcript_object_key IS NOT NULL
            ORDER BY old.execution_no DESC LIMIT 1
            """)
    AnalysisExecution selectReusable(@Param("taskId") String taskId, @Param("executionNo") int executionNo);

    /** 字段名由枚举白名单选出，不接受外部 SQL 标识符。产物必须使用不可覆盖的对象键。 */
    @UpdateProvider(type = CheckpointSql.class, method = "bind")
    int bindOnce(@Param("taskId") String taskId, @Param("executionNo") int executionNo,
                 @Param("artifact") Artifact artifact, @Param("value") String value);

    enum Artifact {
        ASR_TASK_ID("asr_task_id"), TRANSCRIPT("transcript_object_key"),
        CANDIDATES("candidates_object_key"), SEGMENTS("segments_object_key");
        private final String column;
        Artifact(String column) { this.column = column; }
    }

    class CheckpointSql {
        public static String bind(java.util.Map<String, Object> params) {
            Artifact artifact = (Artifact) params.get("artifact");
            String value = (String) params.get("value");
            if (artifact == null || value == null || value.isBlank() || value.contains("://") || value.contains("?")) {
                throw new IllegalArgumentException("产物引用不能为空或为签名 URL");
            }
            String column = artifact.column;
            return "UPDATE analysis_execution SET " + column + " = #{value} "
                    + "WHERE task_id = #{taskId} AND execution_no = #{executionNo} AND " + column + " IS NULL "
                    + "AND EXISTS (SELECT 1 FROM analysis_task t WHERE t.task_id = analysis_execution.task_id "
                    + "AND t.attempt_no = analysis_execution.execution_no AND t.status='RUNNING')";
        }
    }
}
