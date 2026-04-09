package com.videoai.api.controller;

import com.videoai.api.context.UserContext;
import com.videoai.api.service.AuthService;
import com.videoai.api.service.UserService;
import com.videoai.common.domain.User;
import com.videoai.common.dto.request.LoginRequest;
import com.videoai.common.dto.request.RegisterRequest;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.dto.response.AuthResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ApiResponse.success(response);
    }

    /**
     * 获取当前用户的 API Key（需登录）
     */
    @GetMapping("/api-key")
    public ApiResponse<String> getApiKey() {
        User user = UserContext.getUser();
        return ApiResponse.success(user.getMaskedApiKey());
    }
}
