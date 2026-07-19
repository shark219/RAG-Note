package com.rag.notebook.review.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResponse(
        String reviewId,
        String noteId,
        String title,
        String contentPreview,
        List<String> tags,
        String category,
        Integer reviewCount,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastReviewedAt,
        Integer intervalDays
) {}
