package com.rag.notebook.evaluation.service;

import com.rag.notebook.evaluation.entity.EvaluationReport;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.entity.TestCase;
import com.rag.notebook.evaluation.repository.EvaluationReportRepository;
import com.rag.notebook.evaluation.repository.TestCaseRepository;
import com.rag.notebook.rag.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class RegressionTestService {

    private final TestCaseRepository testCaseRepository;
    private final EvaluationService evaluationService;
    private final EvaluationReportRepository reportRepository;
    private final RagService ragService;

    public RegressionTestService(TestCaseRepository testCaseRepository,
                                 EvaluationService evaluationService,
                                 EvaluationReportRepository reportRepository,
                                 RagService ragService) {
        this.testCaseRepository = testCaseRepository;
        this.evaluationService = evaluationService;
        this.reportRepository = reportRepository;
        this.ragService = ragService;
    }

    /**
     * 执行回归测试
     *
     * @param userId 用户 ID
     * @return 回归测试结果摘要
     */
    public Map<String, Object> runRegressionTest(String userId) {
        List<TestCase> testCases = testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (testCases.isEmpty()) {
            return Map.of("status", "no_test_cases", "message", "没有测试用例，请先生成");
        }

        log.info("开始回归测试: 用户={}, 测试用例数={}", userId, testCases.size());

        int total = testCases.size();
        int success = 0;
        double totalScore = 0;
        double totalFaithfulness = 0;
        double totalRelevancy = 0;
        double totalPrecision = 0;
        double totalRecall = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (int i = 0; i < testCases.size(); i++) {
            TestCase testCase = testCases.get(i);
            try {
                // 测试用例之间加延迟，避免 API 过载
                if (i > 0) {
                    Thread.sleep(2000);
                }

                // 1. 执行 RAG 检索 + 生成
                Map<String, Object> ragResult = ragService.getDocumentsAndSummary(userId, testCase.getQuestion());
                String answer = (String) ragResult.get("summary");

                // 2. 构造 RagTrace
                RagTrace trace = new RagTrace();
                trace.setTraceId(UUID.randomUUID().toString().replace("-", ""));
                trace.setUserId(userId);
                trace.setQuery(testCase.getQuestion());
                trace.setFinalAnswer(answer);
                trace.setGroundTruth(testCase.getGroundTruth());

                // 提取检索文档
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> docs = (List<Map<String, Object>>) ragResult.get("documents");
                if (docs != null && !docs.isEmpty()) {
                    List<String> docPreviews = new ArrayList<>();
                    for (Map<String, Object> doc : docs) {
                        String content = (String) doc.getOrDefault("content", "");
                        docPreviews.add(content.length() > 200 ? content.substring(0, 200) : content);
                    }
                    trace.setRetrievedDocs(docPreviews);
                    trace.setRetrievedDocCount(docs.size());

                    double avgSim = docs.stream()
                            .mapToDouble(d -> (double) d.getOrDefault("similarity", 0.0))
                            .average().orElse(0.0);
                    trace.setAvgSimilarity(avgSim);
                }

                // 3. 评估
                EvaluationReport report = evaluationService.evaluate(trace);
                reportRepository.save(report);

                success++;
                totalScore += report.getTotalScore();
                totalFaithfulness += report.getFaithfulness() != null ? report.getFaithfulness() : 0;
                totalRelevancy += report.getAnswerRelevancy() != null ? report.getAnswerRelevancy() : 0;
                totalPrecision += report.getContextPrecision() != null ? report.getContextPrecision() : 0;
                totalRecall += report.getContextRecall() != null ? report.getContextRecall() : 0;

                Map<String, Object> detail = new HashMap<>();
                detail.put("question", testCase.getQuestion());
                detail.put("score", report.getTotalScore());
                detail.put("level", report.getLevel());
                detail.put("faithfulness", report.getFaithfulness());
                detail.put("relevancy", report.getAnswerRelevancy());
                details.add(detail);

                log.info("回归测试 [{}/{}]: {} -> {}分", success, total,
                        testCase.getQuestion().substring(0, Math.min(20, testCase.getQuestion().length())),
                        report.getTotalScore());
            } catch (Exception e) {
                log.warn("回归测试用例失败: {}", e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "completed");
        result.put("total", total);
        result.put("success", success);
        result.put("avgScore", success > 0 ? Math.round(totalScore / success) : 0);
        result.put("avgFaithfulness", success > 0 ? totalFaithfulness / success : 0);
        result.put("avgRelevancy", success > 0 ? totalRelevancy / success : 0);
        result.put("avgPrecision", success > 0 ? totalPrecision / success : 0);
        result.put("avgRecall", success > 0 ? totalRecall / success : 0);
        result.put("details", details);

        log.info("回归测试完成: {}/{} 成功, 平均分={}", success, total, result.get("avgScore"));
        return result;
    }
}
