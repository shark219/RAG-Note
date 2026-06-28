package com.rag.notebook.review.controller;

import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.review.dto.ReviewDoneResponse;
import com.rag.notebook.review.service.ReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> getTodayReviews(@UserId String userId) {
        Map<String, Object> result = reviewService.getTodayReviews(userId);
        return ApiResponse.success(result);
    }

    @PostMapping("/done/{noteId}")
    public ApiResponse<ReviewDoneResponse> markReviewed(@UserId String userId, @PathVariable String noteId) {
        ReviewDoneResponse result = reviewService.markReviewed(userId, noteId);
        return ApiResponse.success(result.message(), result);
    }

    @GetMapping("/question/{noteId}")
    public ApiResponse<Map<String, Object>> generateQuestion(@UserId String userId, @PathVariable String noteId) {
        Map<String, Object> result = reviewService.generateQuestion(userId, noteId);
        return ApiResponse.success(result);
    }
}
