package com.rag.notebook.evaluation.controller;

import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.evaluation.entity.EvaluationReport;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.entity.TestCase;
import com.rag.notebook.evaluation.repository.EvaluationReportRepository;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import com.rag.notebook.evaluation.repository.TestCaseRepository;
import com.rag.notebook.evaluation.service.EvaluationService;
import com.rag.notebook.evaluation.service.RegressionTestService;
import com.rag.notebook.evaluation.service.TestCaseGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RestController
@RequestMapping("/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final RagTraceRepository traceRepository;
    private final EvaluationReportRepository reportRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseGenerator testCaseGenerator;
    private final RegressionTestService regressionTestService;

    public EvaluationController(EvaluationService evaluationService,
                                RagTraceRepository traceRepository,
                                EvaluationReportRepository reportRepository,
                                TestCaseRepository testCaseRepository,
                                TestCaseGenerator testCaseGenerator,
                                RegressionTestService regressionTestService) {
        this.evaluationService = evaluationService;
        this.traceRepository = traceRepository;
        this.reportRepository = reportRepository;
        this.testCaseRepository = testCaseRepository;
        this.testCaseGenerator = testCaseGenerator;
        this.regressionTestService = regressionTestService;
    }

    // ========== 反馈 ==========

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

    // ========== 报告查询 ==========

    @GetMapping("/report/{traceId}")
    public ApiResponse<EvaluationReport> getReport(@PathVariable String traceId) {
        EvaluationReport report = reportRepository.findTopByTraceIdOrderByCreatedAtDesc(traceId).orElse(null);
        if (report == null) {
            return ApiResponse.error(404, "评估报告不存在");
        }
        return ApiResponse.success(report);
    }

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

    // ========== 批量评估 ==========

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

    // ========== 统计 ==========

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();

        Map<String, Object> stats = new HashMap<>();

        stats.put("todayTraceCount", traceRepository.countByDate(todayStart));
        stats.put("todayAvgLatency", traceRepository.avgLatencyByDate(todayStart));
        stats.put("todayAvgFeedback", traceRepository.avgUserFeedbackByDate(todayStart));

        stats.put("weekReportCount", reportRepository.countByDate(weekStart));
        stats.put("weekAvgScore", reportRepository.avgTotalScoreByDate(weekStart));
        stats.put("weekAvgPrecision", reportRepository.avgContextPrecisionByDate(weekStart));
        stats.put("weekAvgRecall", reportRepository.avgContextRecallByDate(weekStart));
        stats.put("weekAvgFaithfulness", reportRepository.avgFaithfulnessByDate(weekStart));
        stats.put("weekAvgRelevancy", reportRepository.avgAnswerRelevancyByDate(weekStart));
        stats.put("weekLowScoreCount", reportRepository.countLowScoreByDate(weekStart));

        stats.put("totalLowScoreReports", reportRepository.findLowScoreReports().size());

        return ApiResponse.success(stats);
    }

    @GetMapping("/low-scores")
    public ApiResponse<List<EvaluationReport>> getLowScores() {
        return ApiResponse.success(reportRepository.findLowScoreReports());
    }

    // ========== Phase 3: 趋势数据 ==========

    @GetMapping("/trend")
    public ApiResponse<Map<String, Object>> getTrend(
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime start = LocalDate.now().minusDays(days).atStartOfDay();
        List<EvaluationReport> reports = reportRepository.findByCreatedAtAfter(start);

        Map<String, List<EvaluationReport>> grouped = reports.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getCreatedAt().toLocalDate().toString()
                ));

        List<String> dates = new ArrayList<>();
        List<Double> faithfulnessTrend = new ArrayList<>();
        List<Double> relevancyTrend = new ArrayList<>();
        List<Double> precisionTrend = new ArrayList<>();
        List<Double> recallTrend = new ArrayList<>();
        List<Double> scoreTrend = new ArrayList<>();

        grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    dates.add(entry.getKey());
                    List<EvaluationReport> dayReports = entry.getValue();
                    faithfulnessTrend.add(avg(dayReports, EvaluationReport::getFaithfulness));
                    relevancyTrend.add(avg(dayReports, EvaluationReport::getAnswerRelevancy));
                    precisionTrend.add(avg(dayReports, EvaluationReport::getContextPrecision));
                    recallTrend.add(avg(dayReports, EvaluationReport::getContextRecall));
                    scoreTrend.add(avgInt(dayReports, EvaluationReport::getTotalScore));
                });

        return ApiResponse.success(Map.of(
                "dates", dates,
                "faithfulness", faithfulnessTrend,
                "answerRelevancy", relevancyTrend,
                "contextPrecision", precisionTrend,
                "contextRecall", recallTrend,
                "totalScore", scoreTrend
        ));
    }

    // ========== Phase 3: 评级分布 ==========

    @GetMapping("/distribution")
    public ApiResponse<Map<String, Object>> getDistribution(
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime start = LocalDate.now().minusDays(days).atStartOfDay();
        List<EvaluationReport> reports = reportRepository.findByCreatedAtAfter(start);

        long excellent = reports.stream().filter(r -> "优秀".equals(r.getLevel())).count();
        long good = reports.stream().filter(r -> "良好".equals(r.getLevel())).count();
        long pass = reports.stream().filter(r -> "及格".equals(r.getLevel())).count();
        long fail = reports.stream().filter(r -> "不及格".equals(r.getLevel())).count();

        return ApiResponse.success(Map.of(
                "excellent", excellent,
                "good", good,
                "pass", pass,
                "fail", fail,
                "total", reports.size()
        ));
    }

    // ========== Phase 3: 诊断分布 ==========

    @GetMapping("/diagnosis-stats")
    public ApiResponse<Map<String, Object>> getDiagnosisStats(
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime start = LocalDate.now().minusDays(days).atStartOfDay();
        List<EvaluationReport> reports = reportRepository.findByCreatedAtAfter(start);

        long hallucination = reports.stream().filter(r -> r.getDiagnosis() != null && r.getDiagnosis().contains("幻觉")).count();
        long irrelevant = reports.stream().filter(r -> r.getDiagnosis() != null && r.getDiagnosis().contains("答非所问")).count();
        long retrievalLow = reports.stream().filter(r -> r.getDiagnosis() != null && r.getDiagnosis().contains("检索精度低")).count();
        long recallLow = reports.stream().filter(r -> r.getDiagnosis() != null && r.getDiagnosis().contains("检索召回低")).count();
        long normal = reports.stream().filter(r -> r.getDiagnosis() != null && r.getDiagnosis().contains("正常")).count();

        return ApiResponse.success(Map.of(
                "hallucination", hallucination,
                "irrelevant", irrelevant,
                "retrievalLow", retrievalLow,
                "recallLow", recallLow,
                "normal", normal
        ));
    }

    // ========== Phase 2: 测试用例管理 ==========

    @PostMapping("/test-cases/generate")
    public ApiResponse<Map<String, Object>> generateTestCases(
            @UserId String userId,
            @RequestParam(defaultValue = "10") int count) {

        int generated = testCaseGenerator.generateTestCases(userId, count);
        return ApiResponse.success("测试用例生成完成", Map.of(
                "generated", generated,
                "total", testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId).size()
        ));
    }

    @GetMapping("/test-cases")
    public ApiResponse<List<TestCase>> getTestCases(@UserId String userId) {
        return ApiResponse.success(testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @DeleteMapping("/test-cases/batch")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteTestCases(@UserId String userId, @RequestBody List<Long> ids) {
        // 验证所有权
        List<TestCase> owned = testCaseRepository.findAllById(ids).stream()
                .filter(tc -> tc.getUserId().equals(userId))
                .toList();
        if (owned.isEmpty()) {
            return ApiResponse.error(400, "没有可删除的测试用例");
        }
        List<Long> ownedIds = owned.stream().map(TestCase::getId).toList();
        int deleted = testCaseRepository.deleteByIds(ownedIds);
        return ApiResponse.success("批量删除完成", Map.of("deleted", deleted));
    }

    @DeleteMapping("/test-cases/{id}")
    public ApiResponse<Object> deleteTestCase(@UserId String userId, @PathVariable Long id) {
        return testCaseRepository.findById(id).map(tc -> {
            if (!tc.getUserId().equals(userId)) {
                return ApiResponse.<Object>error(403, "无权删除他人的测试用例");
            }
            testCaseRepository.deleteById(id);
            return ApiResponse.<Object>success("删除成功");
        }).orElse(ApiResponse.<Object>error(404, "测试用例不存在"));
    }

    @PostMapping("/test-cases/dedup")
    @Transactional
    public ApiResponse<Map<String, Object>> dedupTestCases(@UserId String userId) {
        List<TestCase> duplicates = testCaseRepository.findDuplicatesByUserId(userId);
        if (duplicates.isEmpty()) {
            return ApiResponse.success("没有发现重复的测试用例", Map.of("removed", 0, "total", testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId).size()));
        }

        // 按 question 分组，每组保留最早创建的一条，删除其余
        Map<String, List<TestCase>> grouped = duplicates.stream()
                .collect(Collectors.groupingBy(TestCase::getQuestion));

        Set<Long> toDelete = new HashSet<>();
        for (List<TestCase> group : grouped.values()) {
            // 按创建时间排序，保留最早的一条
            List<TestCase> sorted = group.stream()
                    .sorted(Comparator.comparing(TestCase::getCreatedAt))
                    .toList();
            // 从第 2 条开始删除
            for (int i = 1; i < sorted.size(); i++) {
                toDelete.add(sorted.get(i).getId());
            }
        }

        if (toDelete.isEmpty()) {
            return ApiResponse.success("没有发现需要清理的重复用例", Map.of("removed", 0, "total", testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId).size()));
        }

        int removed = testCaseRepository.deleteByIds(new ArrayList<>(toDelete));
        long remaining = testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId).size();
        log.info("测试用例去重: 删除 {} 条重复记录, 剩余 {} 条", removed, remaining);
        return ApiResponse.success("去重完成", Map.of("removed", removed, "total", remaining));
    }

    // ========== Phase 2: 回归测试 ==========

    @PostMapping("/regression")
    public ApiResponse<Map<String, Object>> runRegressionTest(@UserId String userId) {
        Map<String, Object> result = regressionTestService.runRegressionTest(userId);
        return ApiResponse.success("回归测试完成", result);
    }

    // ========== 工具方法 ==========

    private double avg(List<EvaluationReport> reports, java.util.function.Function<EvaluationReport, Double> getter) {
        return reports.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .mapToDouble(d -> d)
                .average()
                .orElse(0.0);
    }

    private double avgInt(List<EvaluationReport> reports, java.util.function.Function<EvaluationReport, Integer> getter) {
        return reports.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .mapToDouble(d -> d)
                .average()
                .orElse(0.0);
    }

    // ========== 请求体 ==========

    public static class FeedbackRequest {
        private String traceId;
        private Integer score;
        private String reason;

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public Integer getScore() { return score; }
        public void setScore(Integer score) { this.score = score; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
