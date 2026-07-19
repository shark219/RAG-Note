package com.rag.notebook.evaluation.service;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.entity.EvaluationReport;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.repository.EvaluationReportRepository;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EvaluationService {

    private final ModelFactory modelFactory;
    private final RagTraceRepository traceRepository;
    private final EvaluationReportRepository reportRepository;
    private final ApplicationProperties props;

    // 正则模式
    private static final Pattern SCORE_PATTERN = Pattern.compile("SCORE:\\s*([0-9.]+)");
    private static final Pattern CLAIMS_PATTERN = Pattern.compile("CLAIMS:\\s*(.*?)\\s*FAITHFULNESS_SCORE", Pattern.DOTALL);
    private static final Pattern SUPPORTED_PATTERN = Pattern.compile("SUPPORTED");
    private static final Pattern NOT_SUPPORTED_PATTERN = Pattern.compile("NOT_SUPPORTED");
    private static final Pattern QUESTIONS_PATTERN = Pattern.compile("GENERATED_QUESTIONS:\\s*(.*?)\\s*ANSWER_RELEVANCY_SCORE", Pattern.DOTALL);
    private static final Pattern SIMILARITY_PATTERN = Pattern.compile("相似度:\\s*([0-9.]+)");
    private static final Pattern RELEVANCE_PATTERN = Pattern.compile("RELEVANCE:\\s*(.*?)\\s*CONTEXT_PRECISION_SCORE", Pattern.DOTALL);
    private static final Pattern RELEVANT_PATTERN = Pattern.compile("RELEVANT");
    private static final Pattern NOT_RELEVANT_PATTERN = Pattern.compile("NOT_RELEVANT");
    private static final Pattern SENTENCES_PATTERN = Pattern.compile("SENTENCES:\\s*(.*?)\\s*CONTEXT_RECALL_SCORE", Pattern.DOTALL);

    public EvaluationService(ModelFactory modelFactory,
                             RagTraceRepository traceRepository,
                             EvaluationReportRepository reportRepository,
                             ApplicationProperties props) {
        this.modelFactory = modelFactory;
        this.traceRepository = traceRepository;
        this.reportRepository = reportRepository;
        this.props = props;
    }

    /**
     * RAGAS 四指标评估（并行执行 LLM 调用）
     */
    public EvaluationReport evaluate(RagTrace trace) {
        EvaluationReport report = new EvaluationReport();
        report.setTraceId(trace.getTraceId());
        report.setUserId(trace.getUserId());
        report.setQuery(trace.getQuery());

        boolean hasGroundTruth = trace.getGroundTruth() != null && !trace.getGroundTruth().isBlank();
        boolean hasDocs = trace.getRetrievedDocs() != null && !trace.getRetrievedDocs().isEmpty();

        // 并行执行 LLM 评估（3-4 个调用）
        CompletableFuture<Double> faithfulnessFuture = hasDocs
                ? CompletableFuture.supplyAsync(() -> evaluateFaithfulness(trace))
                : CompletableFuture.completedFuture(0.0);
        CompletableFuture<Double> relevancyFuture = CompletableFuture.supplyAsync(() -> evaluateAnswerRelevancy(trace));
        CompletableFuture<Double> precisionFuture = hasDocs
                ? CompletableFuture.supplyAsync(() -> evaluateContextPrecision(trace))
                : CompletableFuture.completedFuture(0.0);
        CompletableFuture<Double> recallFuture = hasGroundTruth
                ? CompletableFuture.supplyAsync(() -> evaluateContextRecall(trace))
                : CompletableFuture.completedFuture(-1.0);

        // 等待所有评估完成
        double faithfulness = joinSafe(faithfulnessFuture, 0.5);
        double relevancy = joinSafe(relevancyFuture, 0.5);
        double precision = joinSafe(precisionFuture, 0.5);
        double recall = joinSafe(recallFuture, -1.0);

        report.setFaithfulness(faithfulness);
        report.setAnswerRelevancy(relevancy);
        report.setContextPrecision(precision);
        report.setContextRecall(recall >= 0 ? recall : null);

        // 规则评估
        int ruleScore = ruleEvaluate(trace);
        report.setRuleScore(ruleScore);

        // 综合评分：调和平均数
        double ruleNormalized = ruleScore / 100.0;
        ApplicationProperties.Evaluation weights = props.getEvaluation();

        double llmScore;
        if (recall >= 0) {
            // 回归测试：四指标加权
            llmScore = faithfulness * weights.getFaithfulnessWeight()
                    + relevancy * weights.getAnswerRelevancyWeight()
                    + precision * weights.getContextPrecisionWeight()
                    + recall * weights.getContextRecallWeight();
        } else {
            // 生产环境：三指标加权（权重归一化）
            double totalWeight = weights.getFaithfulnessWeight()
                    + weights.getAnswerRelevancyWeight()
                    + weights.getContextPrecisionWeight();
            llmScore = faithfulness * weights.getFaithfulnessWeight() / totalWeight
                    + relevancy * weights.getAnswerRelevancyWeight() / totalWeight
                    + precision * weights.getContextPrecisionWeight() / totalWeight;
        }

        double harmonicMean = harmonicMean(llmScore, ruleNormalized);
        int totalScore = (int) Math.round(harmonicMean * 100);
        totalScore = Math.max(0, Math.min(100, totalScore));

        report.setTotalScore(totalScore);
        report.setHarmonicMean(harmonicMean);

        // 评级
        if (totalScore >= 90) report.setLevel("优秀");
        else if (totalScore >= 75) report.setLevel("良好");
        else if (totalScore >= 60) report.setLevel("及格");
        else report.setLevel("不及格");

        // 诊断
        report.setDiagnosis(diagnose(precision, recall, faithfulness, relevancy));

        return report;
    }

    // ========== Faithfulness：声明拆分 + 多层降级解析 ==========

    private static final String FAITHFULNESS_PROMPT = """
            你是一个严格的 RAG 系统评估专家。请评估以下回答的忠实度（Faithfulness）。

            【用户问题】：%s
            【检索到的上下文】：
            %s
            【RAG 系统的回答】：%s

            【评估任务】：
            1. 将回答拆解为独立的原子声明（每行一条）
            2. 对每条声明，判断上下文是否明确支持（SUPPORTED）或不支持（NOT_SUPPORTED）
            3. 忠实度 = SUPPORTED 数量 / 总声明数量

            请严格按以下格式输出，不要包含任何多余文字：
            CLAIMS:
            - [声明1] -> SUPPORTED
            - [声明2] -> NOT_SUPPORTED
            FAITHFULNESS_SCORE: 0.XX
            """;

    private double evaluateFaithfulness(RagTrace trace) {
        String contexts = String.join("\n---\n", trace.getRetrievedDocs());
        String prompt = String.format(FAITHFULNESS_PROMPT, trace.getQuery(), contexts, trace.getFinalAnswer());

        try {
            ChatLanguageModel model = modelFactory.createEvaluationModel();
            Response<AiMessage> response = model.generate(UserMessage.from(prompt));
            String text = response.content().text().trim();

            // 多层降级解析
            double score = parseFaithfulnessScore(text);
            if (score >= 0) {
                log.debug("Faithfulness 评估: {}", String.format("%.2f", score));
                return score;
            }

            log.warn("Faithfulness 解析失败，降级为整体打分");
            return evaluateFaithfulnessFallback(trace);
        } catch (Exception e) {
            log.warn("Faithfulness 评估异常: {}", e.getMessage());
            return evaluateFaithfulnessFallback(trace);
        }
    }

    /**
     * 多层降级解析 Faithfulness 分数
     * 第 1 层：精确匹配 CLAIMS + FAITHFULNESS_SCORE，计算 SUPPORTED 比例
     * 第 2 层：宽松匹配 SUPPORTED/NOT_SUPPORTED 关键词
     * 第 3 层：只提取 FAITHFULNESS_SCORE 数值
     */
    private double parseFaithfulnessScore(String text) {
        // 第 1 层：精确匹配声明拆分
        Matcher claimsMatcher = CLAIMS_PATTERN.matcher(text);
        Matcher scoreMatcher = SCORE_PATTERN.matcher(text);

        if (claimsMatcher.find()) {
            String claimsBlock = claimsMatcher.group(1);
            long supported = SUPPORTED_PATTERN.matcher(claimsBlock).results().count();
            long notSupported = NOT_SUPPORTED_PATTERN.matcher(claimsBlock).results().count();
            long total = supported + notSupported;
            if (total > 0) {
                return (double) supported / total;
            }
        }

        // 第 2 层：宽松匹配关键词
        long supported = SUPPORTED_PATTERN.matcher(text).results().count();
        long notSupported = NOT_SUPPORTED_PATTERN.matcher(text).results().count();
        long total = supported + notSupported;
        if (total > 0) {
            return (double) supported / total;
        }

        // 第 3 层：只提取分数
        if (scoreMatcher.find()) {
            try {
                double score = Double.parseDouble(scoreMatcher.group(1));
                return Math.max(0.0, Math.min(1.0, score));
            } catch (NumberFormatException ignored) {}
        }

        return -1.0; // 解析失败
    }

    /**
     * Faithfulness 降级：整体打分（兼容旧逻辑）
     */
    private double evaluateFaithfulnessFallback(RagTrace trace) {
        String prompt = """
                请评估以下系统回答是否完全基于检索到的文档，有无编造信息（幻觉）。

                检索到的文档：
                %s

                系统回答：%s

                评分标准：
                - 10分：回答完全基于文档，没有任何编造
                - 8分：回答基本基于文档，有极少推测
                - 6分：回答大部分基于文档，但有少量编造
                - 4分：回答有较多编造内容
                - 2分：回答严重脱离文档，大量幻觉

                只返回数字分数（0-10），不要其他文字。
                """.formatted(String.join("\n---\n", trace.getRetrievedDocs()), trace.getFinalAnswer());

        return llmScore(prompt) / 10.0;
    }

    // ========== Answer Relevancy：反向生成 + LLM 自评相似度 ==========

    private static final String ANSWER_RELEVANCY_PROMPT = """
            你是一个严格的 RAG 系统评估专家。请评估以下回答的答案相关性（Answer Relevancy）。

            【原始用户问题】：%s
            【RAG 系统的回答】：%s

            【评估任务】：
            1. 根据提供的回答，反向推导出 3 个可能的用户问题
            2. 计算每个推导出的问题与原始问题的语义相似度（0.0 到 1.0）
            3. 答案相关性 = 平均语义相似度

            请严格按以下格式输出：
            GENERATED_QUESTIONS:
            - [问题1]（相似度: 0.XX）
            - [问题2]（相似度: 0.XX）
            - [问题3]（相似度: 0.XX）
            ANSWER_RELEVANCY_SCORE: 0.XX
            """;

    private double evaluateAnswerRelevancy(RagTrace trace) {
        String prompt = String.format(ANSWER_RELEVANCY_PROMPT, trace.getQuery(), trace.getFinalAnswer());

        try {
            ChatLanguageModel model = modelFactory.createEvaluationModel();
            Response<AiMessage> response = model.generate(UserMessage.from(prompt));
            String text = response.content().text().trim();

            // 尝试从 GENERATED_QUESTIONS 块提取相似度平均值
            Matcher questionsMatcher = QUESTIONS_PATTERN.matcher(text);
            if (questionsMatcher.find()) {
                String questionsBlock = questionsMatcher.group(1);
                Matcher simMatcher = SIMILARITY_PATTERN.matcher(questionsBlock);
                List<Double> similarities = new ArrayList<>();
                while (simMatcher.find()) {
                    try {
                        similarities.add(Double.parseDouble(simMatcher.group(1)));
                    } catch (NumberFormatException ignored) {}
                }
                if (!similarities.isEmpty()) {
                    double avg = similarities.stream().mapToDouble(d -> d).average().orElse(0.5);
                    return Math.max(0.0, Math.min(1.0, avg));
                }
            }

            // 降级：提取 ANSWER_RELEVANCY_SCORE
            Matcher scoreMatcher = SCORE_PATTERN.matcher(text);
            if (scoreMatcher.find()) {
                double score = Double.parseDouble(scoreMatcher.group(1));
                return Math.max(0.0, Math.min(1.0, score));
            }

            log.warn("Answer Relevancy 解析失败，降级为整体打分");
            return evaluateAnswerRelevancyFallback(trace);
        } catch (Exception e) {
            log.warn("Answer Relevancy 评估异常: {}", e.getMessage());
            return evaluateAnswerRelevancyFallback(trace);
        }
    }

    private double evaluateAnswerRelevancyFallback(RagTrace trace) {
        String prompt = """
                请评估以下系统回答是否直接、完整地回应了用户的问题。

                用户问题：%s

                系统回答：%s

                评分标准：
                - 10分：回答直接、完整地回应了问题
                - 8分：回答基本回应了问题，但不够完整
                - 6分：回答部分回应了问题
                - 4分：回答偏离了问题主题
                - 2分：回答完全答非所问

                只返回数字分数（0-10），不要其他文字。
                """.formatted(trace.getQuery(), trace.getFinalAnswer());

        return llmScore(prompt) / 10.0;
    }

    // ========== Context Precision：位置加权 ==========

    private static final String CONTEXT_PRECISION_PROMPT = """
            你是一个严格的 RAG 系统评估专家。请评估上下文精确度（Context Precision）。

            【用户问题】：%s
            【检索到的上下文（按排名顺序）】：
            %s

            【RAG 系统的回答】：%s

            【评估任务】：
            判断每一段上下文是否与回答该问题相关（RELEVANT/NOT_RELEVANT）。
            计算相关文档在排名中的精确度分布，得出加权得分。

            请严格按以下格式输出：
            RELEVANCE:
            - [位置1] -> RELEVANT/NOT_RELEVANT
            - [位置2] -> RELEVANT/NOT_RELEVANT
            CONTEXT_PRECISION_SCORE: 0.XX
            """;

    private double evaluateContextPrecision(RagTrace trace) {
        StringBuilder indexedContexts = new StringBuilder();
        for (int i = 0; i < trace.getRetrievedDocs().size(); i++) {
            indexedContexts.append("[位置").append(i + 1).append("]\n")
                    .append(trace.getRetrievedDocs().get(i)).append("\n\n");
        }

        String prompt = String.format(CONTEXT_PRECISION_PROMPT,
                trace.getQuery(), indexedContexts.toString(), trace.getFinalAnswer());

        try {
            ChatLanguageModel model = modelFactory.createEvaluationModel();
            Response<AiMessage> response = model.generate(UserMessage.from(prompt));
            String text = response.content().text().trim();

            // 尝试解析位置加权分数
            Matcher relevanceMatcher = RELEVANCE_PATTERN.matcher(text);
            if (relevanceMatcher.find()) {
                String relevanceBlock = relevanceMatcher.group(1);
                long relevant = RELEVANT_PATTERN.matcher(relevanceBlock).results().count();
                long notRelevant = NOT_RELEVANT_PATTERN.matcher(relevanceBlock).results().count();
                long total = relevant + notRelevant;
                if (total > 0) {
                    // 位置加权精确度计算
                    return computePositionWeightedPrecision(relevanceBlock, (int) total);
                }
            }

            // 降级：提取分数
            Matcher scoreMatcher = SCORE_PATTERN.matcher(text);
            if (scoreMatcher.find()) {
                double score = Double.parseDouble(scoreMatcher.group(1));
                return Math.max(0.0, Math.min(1.0, score));
            }

            log.warn("Context Precision 解析失败，降级为整体打分");
            return evaluateContextPrecisionFallback(trace);
        } catch (Exception e) {
            log.warn("Context Precision 评估异常: {}", e.getMessage());
            return evaluateContextPrecisionFallback(trace);
        }
    }

    /**
     * 位置加权精确度计算
     * Precision@k = 前 k 个文档中相关文档的比例
     * 最终分数 = Σ(Precision@k × rel(k)) / 相关文档总数
     */
    private double computePositionWeightedPrecision(String relevanceBlock, int total) {
        // 解析每行的 RELEVANT/NOT_RELEVANT
        boolean[] relevances = new boolean[total];
        String[] lines = relevanceBlock.split("\n");
        int idx = 0;
        for (String line : lines) {
            if (idx >= total) break;
            if (line.contains("RELEVANT") && !line.contains("NOT_RELEVANT")) {
                relevances[idx] = true;
            }
            idx++;
        }

        int relevantCount = 0;
        double precisionSum = 0.0;
        for (int k = 0; k < total; k++) {
            if (relevances[k]) {
                relevantCount++;
                precisionSum += (double) relevantCount / (k + 1);
            }
        }

        return relevantCount > 0 ? precisionSum / relevantCount : 0.0;
    }

    private double evaluateContextPrecisionFallback(RagTrace trace) {
        String prompt = """
                请评估以下检索到的文档与用户问题的相关性。

                用户问题：%s

                检索到的文档：
                %s

                评分标准：
                - 10分：所有文档都高度相关
                - 8分：大部分文档相关
                - 6分：一半文档相关
                - 4分：少部分文档相关
                - 2分：几乎没有相关文档

                只返回数字分数（0-10），不要其他文字。
                """.formatted(trace.getQuery(), String.join("\n---\n", trace.getRetrievedDocs()));

        return llmScore(prompt) / 10.0;
    }

    // ========== Context Recall：仅回归测试（需 Ground Truth） ==========

    private static final String CONTEXT_RECALL_PROMPT = """
            你是一个严格的 RAG 系统评估专家。请评估上下文召回率（Context Recall）。

            【用户问题】：%s
            【标准参考答案】：%s
            【检索到的上下文】：
            %s

            【评估任务】：
            1. 将标准参考答案拆解为独立的句子
            2. 对每个句子，判断检索到的上下文是否包含能够支撑该句子的信息
            3. 上下文召回率 = 被支持的句子数 / 总句子数

            请严格按以下格式输出：
            SENTENCES:
            - [句子1] -> SUPPORTED/NOT_SUPPORTED
            - [句子2] -> NOT_SUPPORTED
            CONTEXT_RECALL_SCORE: 0.XX
            """;

    private double evaluateContextRecall(RagTrace trace) {
        if (trace.getGroundTruth() == null || trace.getGroundTruth().isBlank()) {
            return -1.0; // 无 Ground Truth，跳过
        }

        String contexts = String.join("\n---\n", trace.getRetrievedDocs());
        String prompt = String.format(CONTEXT_RECALL_PROMPT,
                trace.getQuery(), trace.getGroundTruth(), contexts);

        try {
            ChatLanguageModel model = modelFactory.createEvaluationModel();
            Response<AiMessage> response = model.generate(UserMessage.from(prompt));
            String text = response.content().text().trim();

            // 解析 SENTENCES 块
            Matcher sentencesMatcher = SENTENCES_PATTERN.matcher(text);
            if (sentencesMatcher.find()) {
                String sentencesBlock = sentencesMatcher.group(1);
                long supported = SUPPORTED_PATTERN.matcher(sentencesBlock).results().count();
                long notSupported = NOT_SUPPORTED_PATTERN.matcher(sentencesBlock).results().count();
                long total = supported + notSupported;
                if (total > 0) {
                    return (double) supported / total;
                }
            }

            // 降级：提取分数
            Matcher scoreMatcher = SCORE_PATTERN.matcher(text);
            if (scoreMatcher.find()) {
                double score = Double.parseDouble(scoreMatcher.group(1));
                return Math.max(0.0, Math.min(1.0, score));
            }

            log.warn("Context Recall 解析失败");
            return 0.5;
        } catch (Exception e) {
            log.warn("Context Recall 评估异常: {}", e.getMessage());
            return 0.5;
        }
    }

    // ========== 规则评估 ==========

    private int ruleEvaluate(RagTrace trace) {
        int score = 100;

        if (trace.getTotalLatencyMs() != null) {
            if (trace.getTotalLatencyMs() > 15000) score -= 20;
            else if (trace.getTotalLatencyMs() > 10000) score -= 15;
            else if (trace.getTotalLatencyMs() > 5000) score -= 8;
        }

        if (trace.getRetrievedDocs() == null || trace.getRetrievedDocs().isEmpty()) {
            score -= 30;
        }

        if (trace.getFinalAnswer() != null && trace.getFinalAnswer().length() < 20) {
            score -= 15;
        }

        if (trace.getAvgSimilarity() != null && trace.getAvgSimilarity() < 0.3) {
            score -= 15;
        }

        return Math.max(0, score);
    }

    // ========== 诊断 ==========

    private String diagnose(double precision, double recall, double faithfulness, double relevancy) {
        StringBuilder diagnosis = new StringBuilder();

        if (precision < 0.6 && recall < 0.6 && recall >= 0) {
            diagnosis.append("检索精度和召回都低，问题在Embedding模型、Chunk切分或召回策略；");
        } else if (precision < 0.6) {
            diagnosis.append("检索精度低，检索到了不相关的文档，需要优化Reranker或TopK；");
        } else if (recall < 0.6 && recall >= 0) {
            diagnosis.append("检索召回低，遗漏了关键文档，需要优化Query扩展或降低TopK阈值；");
        }

        if (faithfulness < 0.6) {
            diagnosis.append("忠实度低，模型存在幻觉，需要优化Prompt约束或更换模型；");
        }

        if (relevancy < 0.6 && faithfulness >= 0.6) {
            diagnosis.append("答案相关性低但忠实度高，模型答非所问，大概率是检索文档不对；");
        }

        if (diagnosis.length() == 0) {
            diagnosis.append("各项指标正常，系统表现良好。");
        }

        return diagnosis.toString();
    }

    // ========== 工具方法 ==========

    /**
     * 调和平均数（0 分保护：0 替换为 0.01）
     */
    public static double harmonicMean(double... scores) {
        double sum = 0.0;
        int count = 0;
        for (double s : scores) {
            if (s > 0) {
                sum += 1.0 / s;
                count++;
            } else if (s == 0) {
                sum += 1.0 / 0.01; // 0 分保护
                count++;
            }
            // s < 0 表示跳过（如无 Ground Truth 的 Context Recall）
        }
        return count == 0 ? 0.0 : count / sum;
    }

    /**
     * 调用 LLM 评分（通用）
     */
    private int llmScore(String prompt) {
        try {
            ChatLanguageModel chatModel = modelFactory.createEvaluationModel();
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            String text = response.content().text().trim();
            text = text.replaceAll("[^0-9]", "");
            if (text.isEmpty()) return 5;
            int score = Integer.parseInt(text);
            return Math.max(0, Math.min(10, score));
        } catch (Exception e) {
            log.warn("LLM Judge evaluation failed: {}", e.getMessage());
            return 5;
        }
    }

    /**
     * 安全等待 CompletableFuture 结果
     */
    private double joinSafe(CompletableFuture<Double> future, double defaultValue) {
        try {
            return future.get();
        } catch (Exception e) {
            log.warn("异步评估失败: {}", e.getMessage());
            return defaultValue;
        }
    }

    /**
     * 批量评估
     */
    public int batchEvaluate(List<RagTrace> traces) {
        int count = 0;
        for (RagTrace trace : traces) {
            try {
                EvaluationReport report = evaluate(trace);
                reportRepository.save(report);
                count++;
            } catch (Exception e) {
                log.warn("Failed to evaluate trace {}: {}", trace.getTraceId(), e.getMessage());
            }
        }
        return count;
    }
}
