package com.videoai.api.controller;

import com.videoai.api.config.GitHubOAuthConfig;
import com.videoai.api.service.OAuthService;
import com.videoai.common.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OAuth 第三方登录控制器
 */
@Slf4j
@RestController
@RequestMapping("/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final GitHubOAuthConfig gitHubConfig;
    private final OAuthService oauthService;

    /**
     * 获取 GitHub 授权 URL
     */
    @GetMapping("/github/url")
    public ApiResponse<Map<String, String>> getGitHubAuthUrl() {
        String url = gitHubConfig.getAuthorizeUrl()
                + "?client_id=" + gitHubConfig.getClientId()
                + "&redirect_uri=" + URLEncoder.encode(gitHubConfig.getRedirectUri(), StandardCharsets.UTF_8)
                + "&scope=read:user";
        return ApiResponse.success(Map.of("url", url));
    }

    /**
     * GitHub OAuth 回调
     * GitHub 重定向到此 → 换 token → 查/建用户 → 签发 JWT → 重定向到前端
     */
    @GetMapping("/github/callback")
    public RedirectView githubCallback(@RequestParam("code") String code,
                                       @RequestParam(value = "error", required = false) String error,
                                       @RequestParam(value = "error_description", required = false) String errorDesc) {
        if (error != null) {
            log.warn("GitHub OAuth denied: {} - {}", error, errorDesc);
            return new RedirectView(buildFrontendUrl("error=" + URLEncoder.encode(errorDesc != null ? errorDesc : error, StandardCharsets.UTF_8)));
        }

        try {
            String token = oauthService.handleGitHubCallback(code);
            return new RedirectView(buildFrontendUrl("token=" + token));
        } catch (Exception e) {
            log.error("GitHub OAuth callback failed", e);
            return new RedirectView(buildFrontendUrl("error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8)));
        }
    }

    private String buildFrontendUrl(String params) {
        return gitHubConfig.getFrontendUrl() + "/#/auth?" + params;
    }
}
