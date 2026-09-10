package com.videoai.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.enums.TaskStatus;
import com.videoai.common.exception.BusinessException;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * 任务服务
 *
 * 直接查询任务表；重试、取消通过条件更新校验归属与当前状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AnalysisTaskMapper analysisTaskMapper;

    /**
     * 查询任务详情
     */
    public AnalysisTask getTask(String taskId) {
        AnalysisTask task = queryTaskFromDatabase(taskId);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        return task;
    }

    /**
     * 分页查询用户任务列表
     */
    public Page<AnalysisTask> listUserTasks(Long userId, int pageNum, int pageSize) {
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1 || pageSize > 50) pageSize = 5;

        Page<AnalysisTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getUserId, userId)
                .ne(AnalysisTask::getStatus, "CANCELLED")
                .orderByDesc(AnalysisTask::getCreatedAt);

        return analysisTaskMapper.selectPage(page, wrapper);
    }

    /**
     * 重命名任务
     */
    public AnalysisTask renameTask(String taskId, Long userId, String taskName) {
        int rows = analysisTaskMapper.renameTask(taskId, userId, taskName);
        if (rows == 0) {
            // 反查区分原因
            AnalysisTask task = getTask(taskId);
            if (!task.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.USER_FORBIDDEN);
            }
            throw new BusinessException(ErrorCode.TASK_STATUS_ERROR);
        }

        return getTask(taskId);
    }

    /**
     * 逻辑删除任务（状态改为CANCELLED）
     */
    public void deleteTask(String taskId, Long userId) {
        int rows = analysisTaskMapper.logicalDelete(taskId, userId);
        if (rows == 0) {
            AnalysisTask task = getTask(taskId);
            if (!task.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.USER_FORBIDDEN);
            }
            throw new BusinessException(ErrorCode.TASK_STATUS_ERROR);
        }

        log.info("Task deleted: taskId={}, userId={}", taskId, userId);
    }

    /**
     * 用户手动重新分析（仅 FAILED 或 PARTIAL 状态）。
     * 递增执行代次并清空旧租约，提交后由后台调度器领取。
     */
    @Transactional
    public AnalysisTask retryTask(String taskId, Long userId) {
        int rows = analysisTaskMapper.resetForManualRetry(taskId, userId);
        if (rows == 0) {
            AnalysisTask task = getTask(taskId);
            if (!task.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.USER_FORBIDDEN);
            }
            TaskStatus status = task.getStatusEnum();
            if (status != TaskStatus.FAILED && status != TaskStatus.PARTIAL) {
                throw new BusinessException(ErrorCode.TASK_STATUS_ERROR, "只有失败或部分完成的任务可以重新分析");
            }
            throw new BusinessException(ErrorCode.TASK_STATUS_ERROR);
        }

        // 在当前事务中返回更新后的数据库记录。
        AnalysisTask task = queryTaskFromDatabase(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        int executionNo = task.getAttemptNo() == null ? 0 : task.getAttemptNo();
        log.info("Task manually resubmitted: taskId={}, executionNo={}", taskId, executionNo);
        return task;
    }

    private AnalysisTask queryTaskFromDatabase(String taskId) {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getTaskId, taskId);
        return analysisTaskMapper.selectOne(wrapper);
    }


}
