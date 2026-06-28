package com.rag.notebook.user.controller;

import com.rag.notebook.auth.AuthService;
import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.user.dto.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ApiResponse.success(response.message(), response);
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        LoginResponse response = authService.register(request);
        return ApiResponse.success(response.message(), response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.substring(7);
        authService.logout(token);
        return ApiResponse.success("用户注销成功");
    }

    @PostMapping("/reset-password")
    public ApiResponse<Map<String, Object>> resetPassword(
            @UserId String userId,
            @Valid @RequestBody ResetPasswordRequest request) {
        Map<String, Object> result = authService.resetPassword(userId, request);
        return ApiResponse.success((String) result.get("message"), result);
    }

    @PostMapping("/token/refresh")
    public ApiResponse<Map<String, Object>> refreshToken(@RequestBody Map<String, String> body) {
        String oldToken = body.get("token");
        Map<String, Object> result = authService.refreshToken(oldToken);
        return ApiResponse.success((String) result.get("message"), result);
    }

    @GetMapping("/detail")
    public ApiResponse<UserResponse> getUserDetail(@UserId String userId) {
        UserResponse user = authService.getUserDetail(userId);
        return ApiResponse.success("获取用户详情成功", user);
    }

    @PutMapping("/update")
    public ApiResponse<Map<String, Object>> updateUser(
            @UserId String userId,
            @RequestBody UserUpdateRequest request) {
        Map<String, Object> result = authService.updateUser(userId, request);
        return ApiResponse.success((String) result.get("message"), result);
    }
}
