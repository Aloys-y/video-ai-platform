package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.AnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
            "updated_at = NOW() " +
            "WHERE task_id = #{taskId} AND status = #{oldStatus}")
    int updateStatusWithCheck(@Param("taskId") String taskId,
                              @Param("oldStatus") String oldStatus,
                              @Param("newStatus") String newStatus);

    /**
     * 开始处理任务
     * 设置状态为PROCESSING，记录开始时间
     */
    @Update("UPDATE analysis_task SET status = 'PROCESSING', " +
            "started_at = NOW(), updated_at = NOW() " +
            "WHERE task_id = #{taskId} AND status IN ('QUEUED', 'RETRYING')")
    int startProcessing(@Param("taskId") String taskId);

    /**
     * 完成任务
     */
    @Update("UPDATE analysis_task SET status = 'COMPLETED', " +
            "progress = 100, completed_at = NOW(), updated_at = NOW(), " +
            "result = #{result}, summary = #{summary}, " +
            "frame_count = #{frameCount}, tokens_used = #{tokensUsed} " +
            "WHERE task_id = #{taskId}")
    int completeTask(@Param("taskId") String taskId,
                     @Param("result") String result,
                     @Param("summary") String summary,
                     @Param("frameCount") Integer frameCount,
                     @Param("tokensUsed") Long tokensUsed);

    /**
     * 任务失败
     */
    @Update("UPDATE analysis_task SET status = 'FAILED', " +
            "error_message = #{errorMessage}, updated_at = NOW() " +
            "WHERE task_id = #{taskId}")
    int failTask(@Param("taskId") String taskId,
                 @Param("errorMessage") String errorMessage);

    /**
     * 增加重试次数并设置状态为RETRYING
     */
    @Update("UPDATE analysis_task SET status = 'RETRYING', " +
            "retry_count = retry_count + 1, updated_at = NOW() " +
            "WHERE task_id = #{taskId}")
    int incrementRetry(@Param("taskId") String taskId);

    /**
     * 标记为最终失败（重试次数耗尽）
     */
    @Update("UPDATE analysis_task SET status = 'DEAD', " +
            "error_message = #{errorMessage}, updated_at = NOW() " +
            "WHERE task_id = #{taskId}")
    int markAsDead(@Param("taskId") String taskId,
                   @Param("errorMessage") String errorMessage);

    /**
     * 更新进度
     */
    @Update("UPDATE analysis_task SET progress = #{progress}, " +
            "updated_at = NOW() WHERE task_id = #{taskId}")
    int updateProgress(@Param("taskId") String taskId,
                       @Param("progress") Integer progress);
}
