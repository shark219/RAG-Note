package com.rag.notebook.evaluation.controller;

import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.evaluation.dto.AblationConfig;
import com.rag.notebook.evaluation.entity.AblationResult;
import com.rag.notebook.evaluation.service.AblationExperimentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/evaluation/ablation")
public class AblationController {

    private final AblationExperimentService ablationExperimentService;

    public AblationController(AblationExperimentService ablationExperimentService) {
        this.ablationExperimentService = ablationExperimentService;
    }

    /**
     * 启动所有 RAG 消融实验（异步，BASELINE + R-1~R-5）
     */
    @PostMapping("/run-all")
    public ApiResponse<Map<String, Object>> runAllExperiments(@UserId String userId) {
        log.info("启动 RAG 消融实验: userId={}", userId);

        CompletableFuture<List<AblationResult>> future =
                ablationExperimentService.runAllRagAblationExperiments(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("message", "RAG Pipeline 消融实验（BASELINE + R-1~R-5）已在后台启动，完成后可在报告中查看结果");
        return ApiResponse.success(result);
    }

    /**
     * 获取最新消融实验报告
     */
    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> getReport(@UserId String userId) {
        Map<String, Object> report = ablationExperimentService.getLatestReport(userId);
        return ApiResponse.success(report);
    }

    /**
     * 获取指定 run_id 的消融实验报告
     */
    @GetMapping("/report/{runId}")
    public ApiResponse<Map<String, Object>> getReportByRunId(@PathVariable String runId) {
        Map<String, Object> report = ablationExperimentService.getReportByRunId(runId);
        return ApiResponse.success(report);
    }

    /**
     * 获取历史 run 列表
     */
    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> getRunHistory(@UserId String userId) {
        return ApiResponse.success(ablationExperimentService.getRunHistory(userId));
    }

    /**
     * Phase 1: 启动 TopK 参数优化实验（异步，R-6a ~ R-6d）
     */
    @PostMapping("/run-topk")
    public ApiResponse<Map<String, Object>> runTopKExperiments(@UserId String userId) {
        log.info("启动 TopK 参数优化实验: userId={}", userId);
        ablationExperimentService.runTopKExperiments(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("message", "TopK 参数优化实验（R-6a ~ R-6d）已在后台启动");
        return ApiResponse.success(result);
    }

    /**
     * 启动 RRF_K 参数优化实验（异步，R-7a ~ R-7d）
     */
    @PostMapping("/run-rrfk")
    public ApiResponse<Map<String, Object>> runRrfKExperiments(@UserId String userId) {
        log.info("启动 RRF_K 参数优化实验: userId={}", userId);
        ablationExperimentService.runRrfKExperiments(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("message", "RRF_K 参数优化实验（R-7a ~ R-7d）已在后台启动");
        return ApiResponse.success(result);
    }

    /**
     * 获取 TopK 参数实验结果（R-6a ~ R-6d）
     */
    @GetMapping("/report/topk")
    public ApiResponse<List<Map<String, Object>>> getTopKResults(@UserId String userId) {
        return ApiResponse.success(ablationExperimentService.getParameterExperimentResults(userId, "R-6"));
    }

    /**
     * 获取 RRF_K 参数实验结果（R-7a ~ R-7d）
     */
    @GetMapping("/report/rrfk")
    public ApiResponse<List<Map<String, Object>>> getRrfKResults(@UserId String userId) {
        return ApiResponse.success(ablationExperimentService.getParameterExperimentResults(userId, "R-7"));
    }

    /**
     * 获取预设的实验配置列表（RAG Pipeline 消融 + 参数实验）
     */
    @GetMapping("/experiments")
    public ApiResponse<List<Map<String, String>>> listExperiments() {
        List<Map<String, String>> list = new ArrayList<>();

        // RAG Pipeline 消融实验
        list.add(Map.of(
                "category", "RAG Pipeline 消融实验",
                "experimentId", "BASELINE",
                "experimentName", "完整 RAG Pipeline",
                "ablationComponent", "none"
        ));
        for (AblationConfig config : AblationConfig.allRagExperiments()) {
            list.add(Map.of(
                    "category", "RAG Pipeline 消融实验",
                    "experimentId", config.getExperimentId(),
                    "experimentName", config.getExperimentName(),
                    "ablationComponent", config.getAblationComponent()
            ));
        }

        // 索引参数实验（独立，不属于消融）
        for (AblationConfig config : AblationConfig.rrfKExperiments()) {
            list.add(Map.of(
                    "category", "索引参数实验",
                    "experimentId", config.getExperimentId(),
                    "experimentName", config.getExperimentName(),
                    "ablationComponent", config.getAblationComponent()
            ));
        }
        for (AblationConfig config : AblationConfig.topKExperiments()) {
            list.add(Map.of(
                    "category", "索引参数实验",
                    "experimentId", config.getExperimentId(),
                    "experimentName", config.getExperimentName(),
                    "ablationComponent", config.getAblationComponent()
            ));
        }
        return ApiResponse.success(list);
    }
}
