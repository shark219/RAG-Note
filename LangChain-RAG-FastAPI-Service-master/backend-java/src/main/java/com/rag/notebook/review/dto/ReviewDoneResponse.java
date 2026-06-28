package com.rag.notebook.review.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewDoneResponse(
        boolean success,
        String message,
        Integer reviewCount,
        Integer intervalDays,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime nextReviewAt
) {}
