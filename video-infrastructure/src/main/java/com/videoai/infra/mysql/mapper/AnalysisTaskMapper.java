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

    /**
     * 更新任务状态（带状态校验）
     *
     * 面试点：为什么WHERE条件要加status校验？
     * 乐观锁思想：防止并发修改导致状态混乱
     *
     * 场景：Worker A和Worker B同时处理同一任务
     * 不加校验：两个Worker都成功，状态最终取决于谁后提交
     * 加校验：只有一个Worker能成功，另一个返回影响行数为0
     */
    @Update("UPDATE analysis_task SET status = #{newStatus}, " +
            "updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND status = #{oldStatus}")
    int updateStatusWithCheck(@Param("taskId") String taskId,
                              @Param("oldStatus") String oldStatus,
                              @Param("newStatus") String newStatus);

    /**
     * 开始处理任务
     * 设置状态为PROCESSING，记录开始时间
     */
    @Update("UPDATE analysis_task SET status = 'PROCESSING', " +
            "started_at = NOW(3), updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND status = 'QUEUED' " +
            "AND retry_count = #{retryCount}")
    int startProcessing(@Param("taskId") String taskId,
                        @Param("retryCount") Integer retryCount);

    /**
     * Outbox 成功投递后将任务标记为已入队
     */
    @Update("UPDATE analysis_task SET status = 'QUEUED', updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND retry_count = #{retryCount} " +
            "AND status = 'PENDING'")
    int markQueued(@Param("taskId") String taskId,
                   @Param("retryCount") Integer retryCount);

    /**
     * 完成任务
     */
    @Update("UPDATE analysis_task SET status = 'COMPLETED', " +
            "progress = 100, completed_at = NOW(3), updated_at = NOW(3), " +
            "result = #{result}, summary = #{summary}, " +
            "frame_count = #{frameCount}, tokens_used = #{tokensUsed} " +
            "WHERE task_id = #{taskId} AND status = 'PROCESSING' " +
            "AND retry_count = #{retryCount}")
    int completeTask(@Param("taskId") String taskId,
                     @Param("retryCount") Integer retryCount,
                     @Param("result") String result,
                     @Param("summary") String summary,
                     @Param("frameCount") Integer frameCount,
                     @Param("tokensUsed") Long tokensUsed);

    /**
     * 当前执行失败。系统不自动重试，等待用户手动重新提交。
     */
    @Update("UPDATE analysis_task SET status = 'FAILED', " +
            "error_message = #{errorMessage}, completed_at = NOW(3), updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND status = 'PROCESSING' " +
            "AND retry_count = #{executionNo}")
    int markFailed(@Param("taskId") String taskId,
                   @Param("executionNo") Integer executionNo,
                   @Param("errorMessage") String errorMessage);

    /**
     * 更新进度
     */
    @Update("UPDATE analysis_task SET progress = #{progress}, " +
            "updated_at = NOW(3) WHERE task_id = #{taskId} " +
            "AND status = 'PROCESSING' AND retry_count = #{retryCount}")
    int updateProgress(@Param("taskId") String taskId,
                       @Param("retryCount") Integer retryCount,
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
     * retry_count 在这里作为单调递增的执行代次，不能清零，否则旧 Kafka 消息可能匹配新执行。
     */
    @Update("UPDATE analysis_task SET status = 'PENDING', " +
            "retry_count = retry_count + 1, progress = 0, error_message = NULL, " +
            "started_at = NULL, completed_at = NULL, updated_at = NOW(3) " +
            "WHERE task_id = #{taskId} AND user_id = #{userId} " +
            "AND status IN ('FAILED', 'DEAD')")
    int resetForManualRetry(@Param("taskId") String taskId,
                            @Param("userId") Long userId);

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

    /**
     * 查询超时仍在处理中的任务
     */
    @Select("SELECT * FROM analysis_task WHERE status = 'PROCESSING' " +
            "AND started_at IS NOT NULL AND started_at < #{deadline} " +
            "ORDER BY started_at ASC LIMIT #{limit}")
    List<AnalysisTask> selectTimedOutProcessingTasks(@Param("deadline") LocalDateTime deadline,
                                                     @Param("limit") int limit);
}
