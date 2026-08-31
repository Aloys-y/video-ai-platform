package com.videoai.api.controller;

import com.videoai.api.service.LoadTestTaskService;
import com.videoai.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 压测专用入口。仅 loadtest profile 激活，并使用独立 Token 防止误调用。
 */
@RestController
@RequestMapping("/load-test")
@Profile("loadtest")
@RequiredArgsConstructor
public class LoadTestController {

    private static final String TOKEN_HEADER = "X-Load-Test-Token";

    private final LoadTestTaskService loadTestTaskService;

    @Value("${videoai.load-test.token:change-me-before-load-test}")
    private String expectedToken;

    @PostMapping("/tasks")
    public ApiResponse<Map<String, Object>> createTask(
            @RequestHeader(TOKEN_HEADER) String token,
            @Valid @RequestBody CreateTaskRequest request) {
        authorize(token);
        return ApiResponse.success(loadTestTaskService.createTask(request.getRunId(), request.getSequence()));
    }

    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> report(
            @RequestHeader(TOKEN_HEADER) String token,
            @RequestHeader("X-Load-Test-Run-Id")
            @NotBlank
            @Pattern(regexp = "^[a-zA-Z0-9_-]{1,32}$") String runId) {
        authorize(token);
        return ApiResponse.success(loadTestTaskService.report(runId));
    }

    private void authorize(String actualToken) {
        boolean matches = actualToken != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actualToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid load-test token");
        }
    }

    @Data
    public static class CreateTaskRequest {
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_-]{1,32}$")
        private String runId;

        @Min(0)
        @Max(1_000_000)
        private int sequence;
    }
}
