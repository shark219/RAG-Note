package com.rag.notebook.evaluation.service;

import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.dto.AblationConfig;
import com.rag.notebook.evaluation.entity.AblationResult;
import com.rag.notebook.evaluation.entity.EvaluationReport;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.entity.TestCase;
import com.rag.notebook.evaluation.repository.AblationResultRepository;
import com.rag.notebook.evaluation.repository.EvaluationReportRepository;
import com.rag.notebook.evaluation.repository.TestCaseRepository;
import com.rag.notebook.rag.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class AblationExperimentService {

    private final RagService ragService;
    private final EvaluationService evaluationService;
    private final EvaluationReportRepository reportRepository;
    private final AblationResultRepository ablationResultRepository;
    private final TestCaseRepository testCaseRepository;
    private final ApplicationProperties props;

    public AblationExperimentService(RagService ragService,
                                      EvaluationService evaluationService,
                                      EvaluationReportRepository reportRepository,
                                      AblationResultRepository ablationResultRepository,
                                      TestCaseRepository testCaseRepository,
                                      ApplicationProperties props) {
        this.ragService = ragService;
        this.evaluationService = evaluationService;
        this.reportRepository = reportRepository;
        this.ablationResultRepository = ablationResultRepository;
        this.testCaseRepository = testCaseRepository;
        this.props = props;
    }

    /**
     * 异步运行所有 RAG 消融实验（R-1 ~ R-7）
     */
    @Async
    public CompletableFuture<List<AblationResult>> runAllRagAblationExperiments(String userId) {
        List<TestCase> testCases = testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (testCases.isEmpty()) {
            log.warn("消融实验: 用户 {} 没有测试用例", userId);
            return CompletableFuture.completedFuture(List.of());
        }

        log.info("开始 RAG 消融实验: 用户={}, 测试用例数={}", userId, testCases.size());

        // 1. 先跑基线
        AblationResult baseline = runExperiment(AblationConfig.baseline(), testCases, userId);
        ablationResultRepository.save(baseline);
        log.info("基线完成: compositeScore={}", baseline.getCompositeScore());

        // 2. 跑 R-1 ~ R-7
        List<AblationResult> results = new ArrayList<>();
        results.add(baseline);

        for (AblationConfig config : AblationConfig.allRagExperiments()) {
            try {
                Thread.sleep(1000); // 避免 API 限流
                AblationResult result = runExperiment(config, testCases, userId);
                result.setBaselineScore(baseline.getCompositeScore());
                result.setDeltaScore(round(result.getCompositeScore() - baseline.getCompositeScore()));
                result.setKeyFindings(generateKeyFindings(result));
                ablationResultRepository.save(result);
                results.add(result);
                log.info("实验 {} 完成: compositeScore={}, delta={}",
                        config.getExperimentId(), result.getCompositeScore(), result.getDeltaScore());
            } catch (Exception e) {
                log.error("实验 {} 失败: {}", config.getExperimentId(), e.getMessage());
            }
        }

        log.info("RAG 消融实验全部完成: {} 个实验", results.size() - 1);
        return CompletableFuture.completedFuture(results);
    }

    /**
     * 运行单个消融实验
     */
    public AblationResult runExperiment(AblationConfig config, List<TestCase> testCases, String userId) {
        double totalFaithfulness = 0, totalRelevancy = 0, totalPrecision = 0, totalRecall = 0;
        double totalDocCount = 0;
        int successCount = 0;

        for (int i = 0; i < testCases.size(); i++) {
            TestCase tc = testCases.get(i);
            try {
                if (i > 0) Thread.sleep(2000); // API 限流

                // 执行 RAG（带消融配置）
                Map<String, Object> ragResult = ragService.getDocumentsAndSummary(userId, tc.getQuestion(), config);
                String answer = (String) ragResult.get("summary");

                // 构造 RagTrace
                RagTrace trace = new RagTrace();
                trace.setTraceId(UUID.randomUUID().toString().replace("-", ""));
                trace.setUserId(userId);
                trace.setQuery(tc.getQuestion());
                trace.setFinalAnswer(answer);
                trace.setGroundTruth(tc.getGroundTruth());

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> docs = (List<Map<String, Object>>) ragResult.get("documents");
                if (docs != null && !docs.isEmpty()) {
                    List<String> docPreviews = docs.stream()
                            .map(d -> (String) d.getOrDefault("content", ""))
                            .map(c -> c.length() > 200 ? c.substring(0, 200) : c)
                            .toList();
                    trace.setRetrievedDocs(docPreviews);
                    trace.setRetrievedDocCount(docs.size());
                }

                // 评估
                EvaluationReport report = evaluationService.evaluate(trace);
                reportRepository.save(report);

                totalFaithfulness += report.getFaithfulness() != null ? report.getFaithfulness() : 0;
                totalRelevancy += report.getAnswerRelevancy() != null ? report.getAnswerRelevancy() : 0;
                totalPrecision += report.getContextPrecision() != null ? report.getContextPrecision() : 0;
                totalRecall += report.getContextRecall() != null ? report.getContextRecall() : 0;
                totalDocCount += docs != null ? docs.size() : 0;
                successCount++;

            } catch (Exception e) {
                log.warn("实验 {} 测试用例 {} 失败: {}", config.getExperimentId(),
                        tc.getQuestion().substring(0, Math.min(30, tc.getQuestion().length())),
                        e.getMessage());
            }
        }

        if (successCount == 0) {
            AblationResult empty = new AblationResult();
            empty.setExperimentId(config.getExperimentId());
            empty.setExperimentName(config.getExperimentName());
            empty.setAblationComponent(config.getAblationComponent());
            empty.setUserId(userId);
            empty.setTestCaseCount(0);
            empty.setCompositeScore(0.0);
            empty.setKeyFindings("所有测试用例均失败");
            return empty;
        }

        double avgFaithfulness = totalFaithfulness / successCount;
        double avgRelevancy = totalRelevancy / successCount;
        double avgPrecision = totalPrecision / successCount;
        double avgRecall = totalRecall / successCount;

        // 加权综合得分（与 EvaluationService 保持一致）
        ApplicationProperties.Evaluation weights = props.getEvaluation();
        double composite;
        if (avgRecall >= 0) {
            composite = avgFaithfulness * weights.getFaithfulnessWeight()
                    + avgRelevancy * weights.getAnswerRelevancyWeight()
                    + avgPrecision * weights.getContextPrecisionWeight()
                    + avgRecall * weights.getContextRecallWeight();
        } else {
            double totalWeight = weights.getFaithfulnessWeight()
                    + weights.getAnswerRelevancyWeight()
                    + weights.getContextPrecisionWeight();
            composite = (avgFaithfulness * weights.getFaithfulnessWeight()
                    + avgRelevancy * weights.getAnswerRelevancyWeight()
                    + avgPrecision * weights.getContextPrecisionWeight()) / totalWeight;
        }

        AblationResult result = new AblationResult();
        result.setExperimentId(config.getExperimentId());
        result.setExperimentName(config.getExperimentName());
        result.setAblationComponent(config.getAblationComponent());
        result.setUserId(userId);
        result.setTestCaseCount(successCount);
        result.setFaithfulness(round(avgFaithfulness));
        result.setAnswerRelevancy(round(avgRelevancy));
        result.setContextPrecision(round(avgPrecision));
        result.setContextRecall(round(avgRecall));
        result.setCompositeScore(round(composite));
        result.setAvgDocCount(round(totalDocCount / successCount));
        result.setConfigSnapshot(configToJson(config));

        return result;
    }

    /**
     * 获取最新的消融实验报告（用于前端展示）
     */
    public Map<String, Object> getLatestReport(String userId) {
        List<AblationResult> results = ablationResultRepository.findAllByUserIdGroupedByExperiment(userId);

        // 去重：每个 experimentId 取最新一条
        Map<String, AblationResult> latestMap = new LinkedHashMap<>();
        for (AblationResult r : results) {
            latestMap.putIfAbsent(r.getExperimentId(), r);
        }

        AblationResult baseline = latestMap.remove("BASELINE");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AblationResult r : latestMap.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experimentId", r.getExperimentId());
            row.put("experimentName", r.getExperimentName());
            row.put("ablationComponent", r.getAblationComponent());
            row.put("compositeScore", r.getCompositeScore());
            row.put("deltaScore", r.getDeltaScore());
            row.put("faithfulness", r.getFaithfulness());
            row.put("answerRelevancy", r.getAnswerRelevancy());
            row.put("contextPrecision", r.getContextPrecision());
            row.put("contextRecall", r.getContextRecall());
            row.put("avgDocCount", r.getAvgDocCount());
            row.put("testCaseCount", r.getTestCaseCount());
            row.put("keyFindings", r.getKeyFindings());
            row.put("createdAt", r.getCreatedAt());
            rows.add(row);
        }

        // 按 deltaScore 排序（影响最大的排在前面）
        rows.sort((a, b) -> {
            Double da = (Double) a.getOrDefault("deltaScore", 0.0);
            Double db = (Double) b.getOrDefault("deltaScore", 0.0);
            return db.compareTo(da);
        });

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("baseline", baseline);
        report.put("experiments", rows);
        report.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        report.put("summary", generateSummary(baseline, rows));

        return report;
    }

    /**
     * 生成实验报告的文字总结
     */
    private String generateSummary(AblationResult baseline, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "暂无实验数据";

        // 找影响最大的组件（delta 绝对值最大）
        Map<String, Object> mostImpactful = rows.get(0);
        double maxImpact = Math.abs((Double) mostImpactful.get("deltaScore"));

        // 找影响最小的组件
        Map<String, Object> leastImpactful = rows.get(rows.size() - 1);

        StringBuilder sb = new StringBuilder();
        sb.append("共完成 ").append(rows.size()).append(" 组 RAG 消融实验。");
        if (maxImpact > 0.02) {
            sb.append("对综合得分影响最大的组件是「")
                    .append(mostImpactful.get("experimentName"))
                    .append("」");
            double delta = (Double) mostImpactful.get("deltaScore");
            sb.append("（Δ=").append(delta > 0 ? "+" : "").append(String.format("%.3f", delta)).append("），");
        }
        sb.append("影响最小的组件是「")
                .append(leastImpactful.get("experimentName"))
                .append("」。");
        sb.append("建议保留对得分贡献大的组件，可考虑移除对得分贡献微弱且耗时较长的组件以优化性能。");

        return sb.toString();
    }

    /**
     * 根据实验结果自动生成关键发现
     */
    private String generateKeyFindings(AblationResult result) {
        if (result.getDeltaScore() == null) return "基线";

        double delta = result.getDeltaScore();
        String impact;
        if (Math.abs(delta) < 0.01) impact = "几乎无影响";
        else if (delta < -0.05) impact = "显著负面影响，该组件对 RAG 质量至关重要";
        else if (delta < -0.02) impact = "中等负面影响，建议保留该组件";
        else if (delta < 0) impact = "轻微负面影响";
        else impact = "正面影响（消融后反而更好，该组件可能引入噪音）";

        StringBuilder sb = new StringBuilder();
        sb.append(impact).append("。");

        // 各项指标分析
        if (result.getFaithfulness() != null && result.getFaithfulness() < 0.5) {
            sb.append("忠实度偏低，可能因检索质量下降导致 LLM 缺乏可靠上下文。");
        }
        if (result.getContextPrecision() != null && result.getContextPrecision() < 0.5) {
            sb.append("检索精确度偏低，召回文档中噪音较多。");
        }
        if (result.getContextRecall() != null && result.getContextRecall() < 0.3) {
            sb.append("检索召回率偏低，相关文档未能被有效检索。");
        }

        return sb.toString();
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private String configToJson(AblationConfig config) {
        return String.format(
                "{\"queryExpansion\":%s,\"vectorSearch\":%s,\"bm25\":%s,\"rrf\":%s,\"rerank\":%s,\"sourceAttr\":%s,\"topK\":%s}",
                config.isQueryExpansionEnabled(),
                config.isVectorSearchEnabled(),
                config.isBm25SearchEnabled(),
                config.isRrfFusionEnabled(),
                config.isRerankEnabled(),
                config.isSourceAttributionEnabled(),
                config.getTopK() != null ? config.getTopK() : "default"
        );
    }
}
