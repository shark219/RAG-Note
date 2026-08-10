package com.rag.notebook.agent.controller;

import com.rag.notebook.agent.metrics.AgentMetrics;
import com.rag.notebook.agent.metrics.AgentMetricsService;
import com.rag.notebook.common.result.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Agent 评估指标 API
 */
@Slf4j
@RestController
@RequestMapping("/agent/metrics")
@RequiredArgsConstructor
public class AgentMetricsController {

    private final AgentMetricsService metricsService;

    /**
     * 获取指定日期范围的评估指标
     */
    @GetMapping
    public ApiResponse<AgentMetrics> getMetrics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        log.info("查询 Agent 指标: startDate={}, endDate={}", startDate, endDate);
        AgentMetrics metrics = metricsService.getMetrics(startDate, endDate);
        return ApiResponse.success(metrics);
    }

    /**
     * 获取最近 N 天的评估指标
     */
    @GetMapping("/recent")
    public ApiResponse<AgentMetrics> getRecentMetrics(
            @RequestParam(defaultValue = "7") int days
    ) {
        log.info("查询最近 {} 天的 Agent 指标", days);
        AgentMetrics metrics = metricsService.getRecentMetrics(days);
        return ApiResponse.success(metrics);
    }

    /**
     * 获取今日指标
     */
    @GetMapping("/today")
    public ApiResponse<AgentMetrics> getTodayMetrics() {
        LocalDate today = LocalDate.now();
        log.info("查询今日 Agent 指标: {}", today);
        AgentMetrics metrics = metricsService.getMetrics(today, today);
        return ApiResponse.success(metrics);
    }
}
