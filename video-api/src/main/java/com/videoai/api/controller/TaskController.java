package com.videoai.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videoai.api.context.UserContext;
import com.videoai.api.service.TaskService;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.domain.User;
import com.videoai.common.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 任务查询接口
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
}
