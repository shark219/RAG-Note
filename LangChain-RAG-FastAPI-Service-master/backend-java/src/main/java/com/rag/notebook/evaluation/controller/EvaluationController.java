package com.rag.notebook.evaluation.controller;

import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.evaluation.entity.EvaluationReport;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.repository.EvaluationReportRepository;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import com.rag.notebook.evaluation.service.EvaluationService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final RagTraceRepository traceRepository;
    private final EvaluationReportRepository reportRepository;

    public EvaluationController(EvaluationService evaluationService,
                                RagTraceRepository traceRepository,
                                EvaluationReportRepository reportRepository) {
        this.evaluationService = evaluationService;
        this.traceRepository = traceRepository;
        this.reportRepository = reportRepository;
    }

    /**
     * 用户反馈：提交对回答的评分
     */
    @PostMapping("/feedback")
    public ApiResponse<Void> submitFeedback(@RequestBody FeedbackRequest request) {
        RagTrace trace = traceRepository.findById(request.getTraceId()).orElse(null);
        if (trace == null) {
            return ApiResponse.error(404, "Trace 不存在");
        }
        trace.setUserFeedback(request.getScore());
        trace.setFeedbackReason(request.getReason());
        traceRepository.save(trace);
        return ApiResponse.success("反馈提交成功");
    }

    /**
     * 查看单条评估报告
     */
    @GetMapping("/report/{traceId}")
    public ApiResponse<EvaluationReport> getReport(@PathVariable String traceId) {
        EvaluationReport report = reportRepository.findTopByTraceIdOrderByCreatedAtDesc(traceId).orElse(null);
        if (report == null) {
            return ApiResponse.error(404, "评估报告不存在");
        }
        return ApiResponse.success(report);
    }

    /**
     * 批量查看评估报告
     */
    @GetMapping("/reports")
    public ApiResponse<List<EvaluationReport>> getReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<EvaluationReport> reports = reportRepository.findAll();
        int start = page * size;
        int end = Math.min(start + size, reports.size());
        if (start >= reports.size()) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(reports.subList(start, end));
    }

    /**
     * 手动触发批量评估
     */
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchEvaluate(
            @RequestParam(defaultValue = "0") int days) {
        LocalDateTime start = LocalDate.now().minusDays(days).atStartOfDay();
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();
        List<RagTrace> traces = traceRepository.findByDateRange(start, end);

        if (traces.isEmpty()) {
            return ApiResponse.success("没有待评估的 Trace", Map.of("count", 0));
        }

        int evaluated = evaluationService.batchEvaluate(traces);
        return ApiResponse.success("评估完成", Map.of(
                "total", traces.size(),
                "evaluated", evaluated
        ));
    }

    /**
     * 查看整体统计
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();

        Map<String, Object> stats = new HashMap<>();

        // 今日统计
        stats.put("todayTraceCount", traceRepository.countByDate(todayStart));
        stats.put("todayAvgLatency", traceRepository.avgLatencyByDate(todayStart));
        stats.put("todayAvgFeedback", traceRepository.avgUserFeedbackByDate(todayStart));

        // 本周统计
        stats.put("weekReportCount", reportRepository.countByDate(weekStart));
        stats.put("weekAvgScore", reportRepository.avgTotalScoreByDate(weekStart));
        stats.put("weekAvgPrecision", reportRepository.avgContextPrecisionByDate(weekStart));
        stats.put("weekAvgRecall", reportRepository.avgContextRecallByDate(weekStart));
        stats.put("weekAvgFaithfulness", reportRepository.avgFaithfulnessByDate(weekStart));
        stats.put("weekAvgRelevancy", reportRepository.avgAnswerRelevancyByDate(weekStart));
        stats.put("weekLowScoreCount", reportRepository.countLowScoreByDate(weekStart));

        // 低分样本数
        stats.put("totalLowScoreReports", reportRepository.findLowScoreReports().size());

        return ApiResponse.success(stats);
    }

    /**
     * 获取低分样本列表
     */
    @GetMapping("/low-scores")
    public ApiResponse<List<EvaluationReport>> getLowScores() {
        return ApiResponse.success(reportRepository.findLowScoreReports());
    }

    // 请求体
    public static class FeedbackRequest {
        private String traceId;
        private Integer score;  // 1-5
        private String reason;

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public Integer getScore() { return score; }
        public void setScore(Integer score) { this.score = score; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
