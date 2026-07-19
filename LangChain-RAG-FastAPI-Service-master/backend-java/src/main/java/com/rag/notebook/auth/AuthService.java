package com.rag.notebook.auth;

import com.rag.notebook.cache.RedisCacheService;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.user.dto.*;
import com.rag.notebook.user.entity.User;
import com.rag.notebook.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
public class AuthService {

    private final UserService userService;
    private final JwtService jwtService;
    private final RedisCacheService redisCacheService;

    public AuthService(UserService userService, JwtService jwtService, RedisCacheService redisCacheService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.redisCacheService = redisCacheService;
    }

    public LoginResponse login(LoginRequest request) {
        if (request.getEmail() == null && request.getUsername() == null) {
            throw new BusinessException("请提供用户名或邮箱");
        }

        User user;
        if (request.getEmail() != null) {
            user = userService.findByEmail(request.getEmail());
        } else {
            user = userService.findByUsername(request.getUsername());
        }

        if (!userService.checkPassword(request.getPassword(), user.getPassword())) {
            throw new BusinessException("密码错误");
        }

        if (user.getStatus() != 1) {
            throw new BusinessException("账户已被禁用");
        }

        user.setLastLogin(LocalDateTime.now());
        userService.save(user);

        String token = jwtService.generateToken(user.getUuid(), user.getUsername(), user.getEmail());

        return new LoginResponse(
                user.getUsername() + " 登录成功",
                toUserResponse(user),
                token
        );
    }

    public LoginResponse register(RegisterRequest request) {
        if (userService.existsByEmail(request.getEmail())) {
            throw new BusinessException("该邮箱已被注册");
        }

        if (request.getTelephone() != null && !request.getTelephone().isEmpty()) {
            if (userService.existsByTelephone(request.getTelephone())) {
                throw new BusinessException("该手机号已被注册");
            }
        }

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("两次输入的密码不一致");
        }

        User user = new User();
        user.setUuid(userService.generateUuid());
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setTelephone(request.getTelephone());
        user.setPassword(userService.encodePassword(request.getPassword()));
        user.setStatus(1);
        user = userService.save(user);

        String token = jwtService.generateToken(user.getUuid(), user.getUsername(), user.getEmail());

        return new LoginResponse(
                user.getUsername() + " 注册成功",
                toUserResponse(user),
                token
        );
    }

    public void logout(String token) {
        try {
            String jti = jwtService.getJtiFromToken(token);
            long ttl = jwtService.getExpirationFromToken(token);
            if (ttl > 0) {
                jwtService.blacklistToken(jti, ttl);
            }
        } catch (Exception e) {
            log.warn("Failed to blacklist token during logout: {}", e.getMessage());
        }
    }

    public Map<String, Object> refreshToken(String oldToken) {
        try {
            String jti = jwtService.getJtiFromToken(oldToken);
            long ttl = jwtService.getExpirationFromToken(oldToken);
            if (ttl > 0) {
                jwtService.blacklistToken(jti, ttl);
            }

            String userId = jwtService.getUserIdFromToken(oldToken);
            User user = userService.findByUuid(userId);
            String newToken = jwtService.generateToken(user.getUuid(), user.getUsername(), user.getEmail());

            return Map.of(
                    "message", "Token刷新成功",
                    "token", newToken,
                    "expire_time", System.currentTimeMillis() + 86400000L
            );
        } catch (Exception e) {
            throw new BusinessException(401, "Token刷新失败: " + e.getMessage());
        }
    }

    public Map<String, Object> resetPassword(String userId, ResetPasswordRequest request) {
        User user = userService.findByUuid(userId);

        if (!userService.checkPassword(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码错误");
        }

        if (request.getNewPassword().equals(request.getOldPassword())) {
            throw new BusinessException("新密码不能与原密码相同");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("两次输入的新密码不一致");
        }

        user.setPassword(userService.encodePassword(request.getNewPassword()));
        userService.save(user);

        redisCacheService.deleteUserCache(userId);

        String newToken = jwtService.generateToken(user.getUuid(), user.getUsername(), user.getEmail());

        return Map.of(
                "message", "密码重置成功",
                "token", newToken
        );
    }

    public UserResponse getUserDetail(String userId) {
        User user = userService.findByUuid(userId);
        return toUserResponse(user);
    }

    public Map<String, Object> updateUser(String userId, UserUpdateRequest request) {
        User user = userService.findByUuid(userId);

        if (request.getUsername() != null) {
            user.setUsername(request.getUsername());
        }
        if (request.getTelephone() != null) {
            if (!request.getTelephone().equals(user.getTelephone()) && userService.existsByTelephone(request.getTelephone())) {
                throw new BusinessException("该手机号已被其他用户使用");
            }
            user.setTelephone(request.getTelephone());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }

        user = userService.save(user);
        redisCacheService.deleteUserCache(userId);

        String newToken = jwtService.generateToken(user.getUuid(), user.getUsername(), user.getEmail());

        return Map.of(
                "message", "用户信息更新成功",
                "user", toUserResponse(user),
                "token", newToken
        );
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getUuid(),
                user.getUsername(),
                user.getEmail(),
                user.getTelephone(),
                user.getGender(),
                user.getBio(),
                user.getAvatar(),
                user.getStatus(),
                user.getDateJoined(),
                user.getLastLogin()
        );
    }
}
