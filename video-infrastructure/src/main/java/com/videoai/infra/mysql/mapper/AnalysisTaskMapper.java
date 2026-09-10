package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.AnalysisTask;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分析任务Mapper
 */
@Mapper
public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {
    @Update("UPDATE analysis_task SET result=#{result},tokens_used=#{tokens},updated_at=NOW(3) WHERE task_id=#{taskId} AND attempt_no=#{attempt} AND status='RUNNING'")
    int storeOutput(@Param("taskId") String taskId,@Param("attempt") int attempt,@Param("result") String result,@Param("tokens") Long tokens);

/**
     * 开始处理任务
     * 设置状态为PROCESSING，记录开始时间
     */
    @Update("UPDATE analysis_task SET status = 'RUNNING', " +
            "started_at = NOW(3), updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND status = 'QUEUED' " +
            "AND attempt_no = #{attemptNo}")
    int startProcessing(@Param("taskId") String taskId,
                        @Param("attemptNo") Integer attemptNo);

/**
     * 完成任务
     */
    @Update("UPDATE analysis_task SET status = 'SUCCEEDED', " +
            "progress = 100, finished_at = NOW(3), updated_at = NOW(3), " +
            "result = #{result}, summary = #{summary}, " +
            "frame_count = #{frameCount}, tokens_used = #{tokensUsed} " +
            "WHERE task_id = #{taskId} AND status = 'RUNNING' " +
            "AND attempt_no = #{attemptNo}")
    @org.apache.ibatis.annotations.Options(timeout = 5)
    int completeTask(@Param("taskId") String taskId,
                     @Param("attemptNo") Integer attemptNo,
                     @Param("result") String result,
                     @Param("summary") String summary,
                     @Param("frameCount") Integer frameCount,
                     @Param("tokensUsed") Long tokensUsed);

/**
     * 更新进度
     */
    @Update("UPDATE analysis_task SET progress = #{progress}, " +
            "updated_at = NOW(3) WHERE task_id = #{taskId} " +
            "AND status = 'RUNNING' AND attempt_no = #{attemptNo}")
    @org.apache.ibatis.annotations.Options(timeout = 5)
    int updateProgress(@Param("taskId") String taskId,
                       @Param("attemptNo") Integer attemptNo,
                       @Param("progress") Integer progress);

    /**
     * 重命名任务（含归属校验）
     */
    @Update("UPDATE analysis_task SET task_name = #{taskName}, " +
            "updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND user_id = #{userId}")
    int renameTask(@Param("taskId") String taskId,
                   @Param("userId") Long userId,
                   @Param("taskName") String taskName);

    /**
     * 用户手动重新分析。
     * attempt_no 在这里作为单调递增的执行代次，不能清零，否则旧执行可能匹配新结果。
     */
    @Update("UPDATE analysis_task SET status = 'PENDING', " +
            "attempt_no = attempt_no + 1, owner_token=NULL, lease_until=NULL, error_code=NULL, progress = 0, error_message = NULL, current_step = NULL, " +
            "started_at = NULL, finished_at = NULL, updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND user_id = #{userId} " +
            "AND status IN ('FAILED', 'PARTIAL')")
    int resetForManualRetry(@Param("taskId") String taskId,
                            @Param("userId") Long userId);

    /** 迟到的旧执行不能改写当前步骤。此字段不参与任务领取。 */
    @Update("UPDATE analysis_task SET current_step = #{step}, updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND attempt_no = #{executionNo} AND status = 'RUNNING'")
    @org.apache.ibatis.annotations.Options(timeout = 5)
    int updateStep(@Param("taskId") String taskId, @Param("executionNo") int executionNo,
                   @Param("step") String step);

    @Select("SELECT COUNT(*) FROM analysis_task WHERE task_id=#{taskId} AND attempt_no=#{executionNo} AND status='RUNNING'")
    @org.apache.ibatis.annotations.Options(timeout = 5)
    int isCurrentProcessing(@Param("taskId") String taskId, @Param("executionNo") int executionNo);

    /**
     * 逻辑删除任务（状态改为CANCELLED）
     * 允许所有状态删除（用户可取消卡死的任务）
     */
    @Update("UPDATE analysis_task SET status = 'CANCELLED', " +
            "updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND user_id = #{userId} " +
            "AND status != 'CANCELLED'")
    int logicalDelete(@Param("taskId") String taskId,
                      @Param("userId") Long userId);

}
