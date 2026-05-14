package com.videoai.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * GitHub OAuth 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oauth.github")
public class GitHubOAuthConfig {

    private String clientId;
    private String clientSecret;
    private String redirectUri = "http://localhost:8080/api/auth/oauth/github/callback";
    private String frontendUrl = "http://localhost:3000";

    /** GitHub OAuth 标准端点，一般不需要改 */
    private String authorizeUrl = "https://github.com/login/oauth/authorize";
    private String tokenUrl = "https://github.com/login/oauth/access_token";
    private String userApiUrl = "https://api.github.com/user";
}
