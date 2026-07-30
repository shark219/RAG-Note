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
     * Phase 1: 异步运行 TopK 参数优化实验（R-9a ~ R-9d）
     */
    @Async
    public CompletableFuture<List<AblationResult>> runTopKExperiments(String userId) {
        return runParameterExperiments(AblationConfig.topKExperiments(), userId, "TopK");
    }

    /**
     * Phase 1: 运行参数优化类实验（TopK / Chunk 等），以参数间对比为主
     */
    private CompletableFuture<List<AblationResult>> runParameterExperiments(
            AblationConfig[] configs, String userId, String componentType) {
        List<TestCase> testCases = testCaseRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (testCases.isEmpty()) {
            log.warn("{} 参数实验: 用户 {} 没有测试用例", componentType, userId);
            return CompletableFuture.completedFuture(List.of());
        }

        log.info("开始 {} 参数优化实验: 用户={}, 测试用例数={}, 配置数={}",
                componentType, userId, testCases.size(), configs.length);

        List<AblationResult> results = new ArrayList<>();
        Double bestScore = null;

        for (int ci = 0; ci < configs.length; ci++) {
            AblationConfig config = configs[ci];
            try {
                if (ci > 0) Thread.sleep(1000);
                AblationResult result = runExperiment(config, testCases, userId);
                if (bestScore != null) {
                    result.setBaselineScore(bestScore);
                    result.setDeltaScore(round(result.getCompositeScore() - bestScore));
                }
                result.setKeyFindings(generateParameterFindings(result, config, componentType));
                ablationResultRepository.save(result);
                results.add(result);

                if (bestScore == null || result.getCompositeScore() > bestScore) {
                    bestScore = result.getCompositeScore();
                }

                log.info("{} 参数实验 {} 完成: score={}, latency={}, token={}",
                        componentType, config.getExperimentId(),
                        result.getCompositeScore(), result.getAvgLatencyMs(), result.getAvgTokenConsumed());
            } catch (Exception e) {
                log.error("{} 参数实验 {} 失败: {}", componentType, config.getExperimentId(), e.getMessage());
            }
        }

        log.info("{} 参数优化实验全部完成", componentType);
        return CompletableFuture.completedFuture(results);
    }

    /**
     * 运行单个消融实验
     */
    public AblationResult runExperiment(AblationConfig config, List<TestCase> testCases, String userId) {
        double totalFaithfulness = 0, totalRelevancy = 0, totalPrecision = 0, totalRecall = 0;
        double totalDocCount = 0;
        long totalLatencyMs = 0, totalRetrievalLatencyMs = 0, totalGenerationLatencyMs = 0;
        long totalTokenConsumed = 0;
        int successCount = 0;

        for (int i = 0; i < testCases.size(); i++) {
            TestCase tc = testCases.get(i);
            try {
                if (i > 0) Thread.sleep(2000); // API 限流

                // 执行 RAG（带消融配置）
                Map<String, Object> ragResult = ragService.getDocumentsAndSummary(userId, tc.getQuestion(), config);
                String answer = (String) ragResult.get("summary");

                // 读取成本数据
                totalLatencyMs += toLong(ragResult.get("totalLatencyMs"));
                totalRetrievalLatencyMs += toLong(ragResult.get("retrievalLatencyMs"));
                totalGenerationLatencyMs += toLong(ragResult.get("generationLatencyMs"));
                totalTokenConsumed += toLong(ragResult.get("tokenConsumed"));

                // 构造 RagTrace
                RagTrace trace = new RagTrace();
                trace.setTraceId(UUID.randomUUID().toString().replace("-", ""));
                trace.setUserId(userId);
                trace.setQuery(tc.getQuestion());
                trace.setFinalAnswer(answer);
                trace.setGroundTruth(tc.getGroundTruth());
                trace.setTotalLatencyMs(toLong(ragResult.get("totalLatencyMs")));
                trace.setRetrievalLatencyMs(toLong(ragResult.get("retrievalLatencyMs")));
                trace.setGenerationLatencyMs(toLong(ragResult.get("generationLatencyMs")));
                trace.setTokenConsumed((int) toLong(ragResult.get("tokenConsumed")));

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
        result.setAvgLatencyMs(totalLatencyMs / successCount);
        result.setAvgRetrievalLatencyMs(totalRetrievalLatencyMs / successCount);
        result.setAvgTokenConsumed((int) (totalTokenConsumed / successCount));
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
            row.put("avgLatencyMs", r.getAvgLatencyMs());
            row.put("avgRetrievalLatencyMs", r.getAvgRetrievalLatencyMs());
            row.put("avgTokenConsumed", r.getAvgTokenConsumed());
            row.put("testCaseCount", r.getTestCaseCount());
            row.put("keyFindings", r.getKeyFindings());
            row.put("createdAt", r.getCreatedAt());
            // 价值评估卡：计算收益/成本比
            if (baseline != null && r.getDeltaScore() != null && r.getAvgLatencyMs() != null) {
                row.put("valueAssessment", buildValueAssessment(r, baseline));
            }
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

        Map<String, Object> mostImpactful = rows.get(0);
        double maxImpact = Math.abs((Double) mostImpactful.get("deltaScore"));
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

        // 成本收益分析
        @SuppressWarnings("unchecked")
        Map<String, Object> bestRoiRow = rows.stream()
                .filter(r -> r.get("valueAssessment") != null)
                .min((a, b) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> va = (Map<String, Object>) a.get("valueAssessment");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vb = (Map<String, Object>) b.get("valueAssessment");
                    double ra = va.get("roi") instanceof Double ? (Double) va.get("roi") : 0;
                    double rb = vb.get("roi") instanceof Double ? (Double) vb.get("roi") : 0;
                    return Double.compare(Math.abs(rb), Math.abs(ra));
                }).orElse(null);

        if (bestRoiRow != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> va = (Map<String, Object>) bestRoiRow.get("valueAssessment");
            sb.append("收益/成本比最高的组件是「").append(bestRoiRow.get("experimentName")).append("」")
                    .append("（决策: ").append(va.get("decision")).append("）。");
        }
        sb.append("建议保留对得分贡献大且成本低的组件，可考虑移除或条件化对得分贡献微弱且成本高的组件。");

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

    /**
     * Phase 1: 参数实验结果的关键发现
     */
    private String generateParameterFindings(AblationResult result, AblationConfig config, String componentType) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s=%s", componentType,
                config.getTopK() != null ? config.getTopK().toString()
                        : config.getChunkSize() != null ? config.getChunkSize().toString()
                        : "default"));
        sb.append(String.format(", 综合得分=%.3f", result.getCompositeScore()));
        sb.append(String.format(", 延迟=%dms", result.getAvgLatencyMs() != null ? result.getAvgLatencyMs() : 0));
        sb.append(String.format(", Token=%d",
                result.getAvgTokenConsumed() != null ? result.getAvgTokenConsumed() : 0));

        if (result.getDeltaScore() != null && Math.abs(result.getDeltaScore()) > 0.01) {
            sb.append(String.format(", Δ=%.3f", result.getDeltaScore()));
        }
        return sb.toString();
    }

    /**
     * Phase 2: 计算模块价值评估卡
     * 收益率 = ΔScore / ΔCost
     * ΔCost = (latency增比例 + token增比例)
     */
    private Map<String, Object> buildValueAssessment(AblationResult experiment, AblationResult baseline) {
        Map<String, Object> card = new LinkedHashMap<>();
        double deltaScore = experiment.getDeltaScore() != null ? experiment.getDeltaScore() : 0;

        long baseLatency = baseline.getAvgLatencyMs() != null ? baseline.getAvgLatencyMs() : 1;
        long expLatency = experiment.getAvgLatencyMs() != null ? experiment.getAvgLatencyMs() : 0;
        int baseToken = baseline.getAvgTokenConsumed() != null ? baseline.getAvgTokenConsumed() : 1;
        int expToken = experiment.getAvgTokenConsumed() != null ? experiment.getAvgTokenConsumed() : 0;

        double latencyRatio = (double) (expLatency - baseLatency) / baseLatency;
        double tokenRatio = (double) (expToken - baseToken) / baseToken;
        double deltaCost = latencyRatio + tokenRatio;

        card.put("deltaScore", round(deltaScore));
        card.put("deltaLatencyMs", expLatency - baseLatency);
        card.put("deltaToken", expToken - baseToken);
        card.put("deltaCost", round(deltaCost));

        // 收益率 = 效果变化 / 成本变化（成本下降为正，成本上升为负）
        double roi;
        if (Math.abs(deltaCost) < 0.001) {
            roi = Math.abs(deltaScore) > 0.01 ? Double.POSITIVE_INFINITY : 0;
            card.put("roi", roi);
        } else {
            roi = deltaScore / deltaCost;
            card.put("roi", round(roi));
        }

        // 决策建议
        card.put("decision", recommendDecision(deltaScore, deltaCost, roi));

        return card;
    }

    private String recommendDecision(double deltaScore, double deltaCost, double roi) {
        // 消融后得分降低 → 即原模块有正面贡献
        boolean moduleHelps = deltaScore < 0;
        boolean moduleCosts = deltaCost > 0.05;

        if (!moduleHelps && Math.abs(deltaScore) < 0.01) {
            return "可移除——对效果几乎无影响";
        }
        if (moduleHelps && !moduleCosts) {
            return "强烈保留——有效果且成本低";
        }
        if (moduleHelps && moduleCosts) {
            if (Math.abs(roi) > 10) return "保留——效果好，性价比高";
            if (Math.abs(roi) > 3) return "建议保留——以中等成本换取明显改善";
            return "需优化——效果好但成本偏高，考虑条件启停";
        }
        if (!moduleHelps && Math.abs(deltaScore) > 0.02) {
            return "需优化——当前配置下引入噪音，建议调参后再评估";
        }
        return "待进一步分析";
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        return 0L;
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
