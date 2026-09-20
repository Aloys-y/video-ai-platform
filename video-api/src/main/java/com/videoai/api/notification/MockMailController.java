package com.videoai.api.notification;

import com.videoai.api.context.UserContext;
import com.videoai.api.service.TaskSegmentService;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.infra.notification.MockMailService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@ConditionalOnProperty(name="notification.mock.enabled",havingValue="true")
public class MockMailController {
    private final TaskSegmentService tasks;
    private final MockMailService mail;
    public MockMailController(TaskSegmentService tasks,MockMailService mail) {this.tasks=tasks;this.mail=mail;}
    @GetMapping("/task/{taskId}/mock-notifications")
    public ApiResponse<List<Map<String,Object>>> list(@PathVariable String taskId) {
        tasks.ownedTask(taskId,UserContext.getUserId());
        return ApiResponse.success(mail.list(taskId));
    }
}
