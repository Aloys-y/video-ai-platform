package com.videoai.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videoai.api.context.UserContext;
import com.videoai.api.service.TaskService;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.domain.User;
import com.videoai.common.dto.request.RenameTaskRequest;
import com.videoai.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 任务接口
 */
@RestController
@RequestMapping("/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /**
     * 查询任务详情
     */
    @GetMapping("/{taskId}")
    public ApiResponse<AnalysisTask> getTask(@PathVariable("taskId") String taskId) {
        AnalysisTask task = taskService.getTask(taskId);
        return ApiResponse.success(task);
    }

    /**
     * 查询当前用户的任务列表（分页）
     */
    @GetMapping("/list")
    public ApiResponse<Page<AnalysisTask>> listTasks(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        User user = UserContext.getUser();
        Page<AnalysisTask> result = taskService.listUserTasks(user.getId(), page, size);
        return ApiResponse.success(result);
    }

    /**
     * 重命名任务
     */
    @PutMapping("/{taskId}/rename")
    public ApiResponse<AnalysisTask> renameTask(
            @PathVariable("taskId") String taskId,
            @Valid @RequestBody RenameTaskRequest request) {
        User user = UserContext.getUser();
        AnalysisTask task = taskService.renameTask(taskId, user.getId(), request.getTaskName());
        return ApiResponse.success(task);
    }

    /**
     * 删除任务（逻辑删除，状态改为CANCELLED）
     */
    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable("taskId") String taskId) {
        User user = UserContext.getUser();
        taskService.deleteTask(taskId, user.getId());
        return ApiResponse.success();
    }

    /**
     * 重试任务（仅FAILED/DEAD状态可重试）
     */
    @PostMapping("/{taskId}/retry")
    public ApiResponse<AnalysisTask> retryTask(@PathVariable("taskId") String taskId) {
        User user = UserContext.getUser();
        AnalysisTask task = taskService.retryTask(taskId, user.getId());
        return ApiResponse.success(task);
    }
}
