package com.rag.notebook.user.dto;

public record LoginResponse(
        String message,
        UserResponse user,
        String token
) {}
