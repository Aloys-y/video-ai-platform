package com.videoai.api.controller;

import com.videoai.api.context.UserContext;
import com.videoai.api.service.UserService;
import com.videoai.common.domain.User;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试控制器
 * 用于验证认证和限流功能
 *
 * 注意：生产环境应该删除或禁用此Controller
 */
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
@Profile("dev")
public class TestController {

    private final UserService userService;

    /**
     * 创建测试用户（仅开发环境使用）
     * 访问此接口不需要认证
     */
    @PostMapping("/user/create")
    public ApiResponse<Map<String, String>> createTestUser(@jakarta.validation.Valid @RequestBody CreateUserRequest request) {
        User user = userService.createUser(
                request.getUsername(),
                request.getEmail(),
                UserRole.USER
        );

        Map<String, String> result = new HashMap<>();
        result.put("userId", user.getUserId());
        result.put("username", user.getUsername());
        result.put("apiKey", user.getApiKey());

        return ApiResponse.success(result);
    }

    /**
     * 测试认证接口
     * 需要在Header中携带X-API-Key
     */
    @GetMapping("/auth")
    public ApiResponse<Map<String, Object>> testAuth() {
        User user = UserContext.getUser();

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getUserId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        result.put("message", "认证成功！");

        return ApiResponse.success(result);
    }

    /**
     * 测试限流接口
     * 快速访问会触发限流
     */
    @GetMapping("/rate-limit")
    public ApiResponse<String> testRateLimit() {
        return ApiResponse.success("请求成功，当前时间：" + System.currentTimeMillis());
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 2, max = 64, message = "用户名长度2-64字符")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅允许字母、数字、下划线")
        private String username;

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱最长128字符")
        private String email;
    }
}
