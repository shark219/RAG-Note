package com.rag.notebook.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(
        String uuid,
        String username,
        String email,
        String telephone,
        Integer gender,
        String bio,
        String avatar,
        Integer status,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime dateJoined,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastLogin
) {}
