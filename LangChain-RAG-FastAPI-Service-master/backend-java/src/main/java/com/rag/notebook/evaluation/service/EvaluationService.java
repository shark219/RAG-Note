package com.rag.notebook.evaluation.service;

import com.rag.notebook.agent.ModelFactory;
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

import java.util.List;

@Slf4j
@Service
public class EvaluationService {

    private final ModelFactory modelFactory;
    private final RagTraceRepository traceRepository;
    private final EvaluationReportRepository reportRepository;

    public EvaluationService(ModelFactory modelFactory,
                             RagTraceRepository traceRepository,
                             EvaluationReportRepository reportRepository) {
        this.modelFactory = modelFactory;
        this.traceRepository = traceRepository;
        this.reportRepository = reportRepository;
    }

    /**
     * 四指标评估：Context Precision, Context Recall, Faithfulness, Answer Relevancy
     */
    public EvaluationReport evaluate(RagTrace trace) {
        EvaluationReport report = new EvaluationReport();
        report.setTraceId(trace.getTraceId());
        report.setUserId(trace.getUserId());
        report.setQuery(trace.getQuery());

        // 1. Context Precision：检索的文档是否相关
        double precision = evaluateContextPrecision(trace);

        // 2. Context Recall：是否找全了
        double recall = evaluateContextRecall(trace);

        // 3. Faithfulness：有没有幻觉
        double faithfulness = evaluateFaithfulness(trace);

        // 4. Answer Relevancy：有没有答到点上
        double relevancy = evaluateAnswerRelevancy(trace);

        report.setContextPrecision(precision);
        report.setContextRecall(recall);
        report.setFaithfulness(faithfulness);
        report.setAnswerRelevancy(relevancy);

        // 规则评估：耗时、Token 等
        int ruleScore = ruleEvaluate(trace);
        report.setRuleScore(ruleScore);

        // 综合评分：四指标 70% + 规则 30%
        int totalScore = (int) ((precision + recall + faithfulness + relevancy) / 4 * 70 + ruleScore * 0.3);
        totalScore = Math.max(0, Math.min(100, totalScore));
        report.setTotalScore(totalScore);

        // 评级
        if (totalScore >= 90) report.setLevel("优秀");
        else if (totalScore >= 75) report.setLevel("良好");
        else if (totalScore >= 60) report.setLevel("及格");
        else report.setLevel("不及格");

        // 诊断
        report.setDiagnosis(diagnose(precision, recall, faithfulness, relevancy));

        return report;
    }

    /**
     * Context Precision：检索文档跟问题的相关性
     */
    private double evaluateContextPrecision(RagTrace trace) {
        if (trace.getRetrievedDocs() == null || trace.getRetrievedDocs().isEmpty()) {
            return 0.0;
        }
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

    /**
     * Context Recall：检索文档是否覆盖了回答所需信息
     */
    private double evaluateContextRecall(RagTrace trace) {
        if (trace.getRetrievedDocs() == null || trace.getRetrievedDocs().isEmpty()) {
            return 0.0;
        }
        String prompt = """
                请评估以下检索到的文档是否覆盖了回答用户问题所需的全部信息。

                用户问题：%s

                系统回答：%s

                检索到的文档：
                %s

                评分标准：
                - 10分：文档完全覆盖了回答所需的所有信息
                - 8分：覆盖了大部分关键信息
                - 6分：覆盖了一半关键信息
                - 4分：只覆盖了少部分信息
                - 2分：几乎没有覆盖到相关信息

                只返回数字分数（0-10），不要其他文字。
                """.formatted(trace.getQuery(), trace.getFinalAnswer(),
                String.join("\n---\n", trace.getRetrievedDocs()));

        return llmScore(prompt) / 10.0;
    }

    /**
     * Faithfulness：回答是否基于检索文档（有无幻觉）
     */
    private double evaluateFaithfulness(RagTrace trace) {
        if (trace.getRetrievedDocs() == null || trace.getRetrievedDocs().isEmpty()) {
            return 0.0;
        }
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

    /**
     * Answer Relevancy：回答是否回应了用户问题
     */
    private double evaluateAnswerRelevancy(RagTrace trace) {
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

    /**
     * 规则评估：耗时、Token 等硬指标
     */
    private int ruleEvaluate(RagTrace trace) {
        int score = 100;

        // 耗时扣分
        if (trace.getTotalLatencyMs() != null) {
            if (trace.getTotalLatencyMs() > 15000) score -= 20;
            else if (trace.getTotalLatencyMs() > 10000) score -= 15;
            else if (trace.getTotalLatencyMs() > 5000) score -= 8;
        }

        // 检索为空扣分
        if (trace.getRetrievedDocs() == null || trace.getRetrievedDocs().isEmpty()) {
            score -= 30;
        }

        // 回答太短扣分
        if (trace.getFinalAnswer() != null && trace.getFinalAnswer().length() < 20) {
            score -= 15;
        }

        // 相似度过低扣分
        if (trace.getAvgSimilarity() != null && trace.getAvgSimilarity() < 0.3) {
            score -= 15;
        }

        return Math.max(0, score);
    }

    /**
     * 诊断：根据四指标反推问题出在哪
     */
    private String diagnose(double precision, double recall, double faithfulness, double relevancy) {
        StringBuilder diagnosis = new StringBuilder();

        if (precision < 0.6 && recall < 0.6) {
            diagnosis.append("检索精度和召回都低，问题在Embedding模型、Chunk切分或召回策略；");
        } else if (precision < 0.6) {
            diagnosis.append("检索精度低但召回正常，检索到了不相关的文档，需要优化Reranker或TopK；");
        } else if (recall < 0.6) {
            diagnosis.append("检索召回低但精度正常，遗漏了关键文档，需要优化Query扩展或降低TopK阈值；");
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

    /**
     * 调用 LLM 评分
     */
    private int llmScore(String prompt) {
        try {
            ChatLanguageModel chatModel = modelFactory.createChatModel();
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            String text = response.content().text().trim();
            // 提取数字
            text = text.replaceAll("[^0-9]", "");
            if (text.isEmpty()) return 5;
            int score = Integer.parseInt(text);
            return Math.max(0, Math.min(10, score));
        } catch (Exception e) {
            log.warn("LLM Judge evaluation failed: {}", e.getMessage());
            return 5; // 默认中间分
        }
    }

    /**
     * 批量评估当天的 Trace
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
