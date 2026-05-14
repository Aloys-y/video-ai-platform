package com.videoai.api.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.videoai.api.config.GitHubOAuthConfig;
import com.videoai.api.util.JwtUtil;
import com.videoai.common.domain.User;
import com.videoai.common.enums.UserRole;
import com.videoai.infra.mysql.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * OAuth 第三方登录服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final GitHubOAuthConfig gitHubConfig;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final UserService userService;

    private static final OkHttpClient HTTP = new OkHttpClient();

    /**
     * GitHub OAuth 回调处理
     * @param code  GitHub 授权回调携带的 code
     * @return JWT token
     */
    public String handleGitHubCallback(String code) throws IOException {
        // 1. code → access_token
        String accessToken = exchangeGitHubCode(code);
        // 2. access_token → GitHub user info
        GitHubUserInfo gh = fetchGitHubUser(accessToken);
        // 3. 查找或创建本地用户
        User user = findOrCreateUser(gh);
        // 4. 签发 JWT
        return jwtUtil.generateToken(user);
    }

    private String exchangeGitHubCode(String code) throws IOException {
        JSONObject body = new JSONObject();
        body.set("client_id", gitHubConfig.getClientId());
        body.set("client_secret", gitHubConfig.getClientSecret());
        body.set("code", code);

        Request request = new Request.Builder()
                .url(gitHubConfig.getTokenUrl())
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .header("Accept", "application/json")
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String resp = response.body().string();
            JSONObject json = JSONUtil.parseObj(resp);
            if (json.containsKey("error")) {
                throw new IOException("GitHub token exchange failed: " + json.getStr("error_description"));
            }
            return json.getStr("access_token");
        }
    }

    private GitHubUserInfo fetchGitHubUser(String accessToken) throws IOException {
        Request request = new Request.Builder()
                .url(gitHubConfig.getUserApiUrl())
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String resp = response.body().string();
            JSONObject json = JSONUtil.parseObj(resp);
            GitHubUserInfo info = new GitHubUserInfo();
            info.id = json.getLong("id");
            info.login = json.getStr("login");
            info.email = json.getStr("email");
            info.name = json.getStr("name");
            info.avatarUrl = json.getStr("avatar_url");
            return info;
        }
    }

    private User findOrCreateUser(GitHubUserInfo gh) {
        // 先查是否已有关联用户
        User existing = userMapper.selectByOAuth("github", String.valueOf(gh.id));
        if (existing != null) {
            log.info("OAuth user found: github_id={}, userId={}", gh.id, existing.getUserId());
            return existing;
        }

        // 创建新用户
        String username = gh.login;
        // 如果用户名已存在，追加 GitHub ID
        if (userMapper.selectByUsername(username) != null) {
            username = gh.login + "_" + gh.id;
        }

        User user = new User();
        user.setUserId(generateUserId());
        user.setUsername(username);
        user.setEmail(gh.email);
        user.setApiKey(userService.generateApiKey(true));
        user.setApiSecret(userService.hashSecret(userService.generateApiSecret()));
        user.setOauthProvider("github");
        user.setOauthProviderId(String.valueOf(gh.id));
        user.setRole(UserRole.USER.getCode());
        user.setStatus(1);
        user.setRateLimit(UserRole.USER.getDefaultRateLimit());

        userMapper.insert(user);
        log.info("OAuth user created: github_id={}, userId={}, username={}", gh.id, user.getUserId(), username);
        return user;
    }

    private String generateUserId() {
        return "usr_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static class GitHubUserInfo {
        long id;
        String login;
        String email;
        String name;
        String avatarUrl;
    }
}
