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
     * 启动所有 RAG 消融实验（异步，R-1 ~ R-7）
     */
    @PostMapping("/run-all")
    public ApiResponse<Map<String, Object>> runAllExperiments(@UserId String userId) {
        log.info("启动 RAG 消融实验: userId={}", userId);

        CompletableFuture<List<AblationResult>> future =
                ablationExperimentService.runAllRagAblationExperiments(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("message", "RAG 消融实验（R-1 ~ R-7）已在后台启动，完成后可在报告中查看结果");
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
     * 获取预设的实验配置列表
     */
    @GetMapping("/experiments")
    public ApiResponse<List<Map<String, String>>> listExperiments() {
        List<Map<String, String>> list = new ArrayList<>();
        // 基线
        AblationConfig baseline = AblationConfig.baseline();
        list.add(Map.of(
                "experimentId", baseline.getExperimentId(),
                "experimentName", baseline.getExperimentName(),
                "ablationComponent", baseline.getAblationComponent()
        ));
        // R-1 ~ R-7
        for (AblationConfig config : AblationConfig.allRagExperiments()) {
            list.add(Map.of(
                    "experimentId", config.getExperimentId(),
                    "experimentName", config.getExperimentName(),
                    "ablationComponent", config.getAblationComponent()
            ));
        }
        return ApiResponse.success(list);
    }
}
