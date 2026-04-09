package com.videoai.api.service;

import com.videoai.api.util.JwtUtil;
import com.videoai.common.domain.User;
import com.videoai.common.dto.request.LoginRequest;
import com.videoai.common.dto.request.RegisterRequest;
import com.videoai.common.dto.response.AuthResponse;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.enums.UserRole;
import com.videoai.infra.mysql.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserService userService;
    private final JwtUtil jwtUtil;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // 检查用户名是否已存在
        if (userMapper.selectByUsername(request.getUsername()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        // 检查邮箱是否已注册
        if (userMapper.selectByEmail(request.getEmail()) != null) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }

        // 创建用户（生成 API Key）
        User user = userService.createUser(
                request.getUsername(),
                request.getEmail(),
                UserRole.USER,
                request.getPassword()
        );

        // 生成 JWT
        String token = jwtUtil.generateToken(user);

        return AuthResponse.builder()
                .token(token)
                .userId(user.getUserId())
                .username(user.getUsername())
                .role(user.getRole())
                .apiKey(user.getMaskedApiKey())
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        // 根据邮箱查找用户
        User user = userMapper.selectByEmail(request.getEmail());
        if (user == null) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 校验密码
        if (user.getPassword() == null || !BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 检查用户状态
        if (!user.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_FORBIDDEN);
        }

        // 生成 JWT
        String token = jwtUtil.generateToken(user);

        return AuthResponse.builder()
                .token(token)
                .userId(user.getUserId())
                .username(user.getUsername())
                .role(user.getRole())
                .apiKey(user.getMaskedApiKey())
                .build();
    }

    /**
     * 业务异常（内部使用，避免引入新文件）
     */
    public static class BusinessException extends RuntimeException {
        private final ErrorCode errorCode;

        public BusinessException(ErrorCode errorCode) {
            super(errorCode.getMessage());
            this.errorCode = errorCode;
        }

        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }
}
