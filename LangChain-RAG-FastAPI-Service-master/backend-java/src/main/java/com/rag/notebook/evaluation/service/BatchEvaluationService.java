package com.rag.notebook.evaluation.service;

import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.entity.EvaluationReport;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.repository.EvaluationReportRepository;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
public class BatchEvaluationService {

    private final RagTraceRepository traceRepository;
    private final EvaluationReportRepository reportRepository;
    private final EvaluationService evaluationService;
    private final ApplicationProperties props;
    private final Random random = new Random();

    public BatchEvaluationService(RagTraceRepository traceRepository,
                                  EvaluationReportRepository reportRepository,
                                  EvaluationService evaluationService,
                                  ApplicationProperties props) {
        this.traceRepository = traceRepository;
        this.reportRepository = reportRepository;
        this.evaluationService = evaluationService;
        this.props = props;
    }

    /**
     * 每天凌晨 2 点自动评估前一天的 Trace（带采样策略）
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyEvaluation() {
        log.info("开始每日评估任务...");

        LocalDateTime start = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime end = LocalDate.now().atStartOfDay();

        List<RagTrace> traces = traceRepository.findByDateRange(start, end);
        if (traces.isEmpty()) {
            log.info("没有待评估的 Trace");
            return;
        }

        log.info("发现 {} 条待评估的 Trace", traces.size());

        // 采样策略
        List<RagTrace> toEvaluate = sampleTraces(traces);
        log.info("采样后 {} 条待评估", toEvaluate.size());

        int evaluated = evaluationService.batchEvaluate(toEvaluate);

        log.info("每日评估完成: 总计 {} 条，采样 {} 条，成功评估 {} 条",
                traces.size(), toEvaluate.size(), evaluated);

        // 输出低分样本统计
        List<EvaluationReport> lowScores = reportRepository.findLowScoreReports();
        log.info("累计低分样本: {} 条", lowScores.size());
    }

    /**
     * 采样策略：
     * - 高优先级：用户低分反馈（1-2 分）→ 必须评估
     * - 高优先级：规则评估预判不及格（耗时 > 15s 或检索为空）→ 必须评估
     * - 中优先级：其他 Trace → 按比例采样
     * - 低优先级：高相似度（>0.8）+ 短耗时（<5s）→ 跳过
     */
    private List<RagTrace> sampleTraces(List<RagTrace> traces) {
        double sampleRate = props.getEvaluation().getSampleRate();
        List<RagTrace> result = new ArrayList<>();

        for (RagTrace trace : traces) {
            // 高优先级：用户低分反馈 → 必须评估
            if (trace.getUserFeedback() != null && trace.getUserFeedback() <= 2) {
                result.add(trace);
                continue;
            }

            // 高优先级：规则预判不及格 → 必须评估
            if (isRuleFail(trace)) {
                result.add(trace);
                continue;
            }

            // 低优先级：高相似度 + 短耗时 → 跳过
            if (isHighQuality(trace)) {
                continue;
            }

            // 中优先级：按比例采样
            if (random.nextDouble() < sampleRate) {
                result.add(trace);
            }
        }

        return result;
    }

    /**
     * 规则预判：是否大概率不及格
     */
    private boolean isRuleFail(RagTrace trace) {
        // 耗时过长
        if (trace.getTotalLatencyMs() != null && trace.getTotalLatencyMs() > 15000) {
            return true;
        }
        // 检索为空
        if (trace.getRetrievedDocs() == null || trace.getRetrievedDocs().isEmpty()) {
            return true;
        }
        // 回答过短
        if (trace.getFinalAnswer() != null && trace.getFinalAnswer().length() < 20) {
            return true;
        }
        return false;
    }

    /**
     * 高质量判断：大概率没问题，可跳过评估
     */
    private boolean isHighQuality(RagTrace trace) {
        boolean highSimilarity = trace.getAvgSimilarity() != null && trace.getAvgSimilarity() > 0.8;
        boolean fastResponse = trace.getTotalLatencyMs() != null && trace.getTotalLatencyMs() < 5000;
        return highSimilarity && fastResponse;
    }

    /**
     * 每周一凌晨 3 点输出周报统计
     */
    @Scheduled(cron = "0 0 3 ? * MON")
    public void weeklyReport() {
        log.info("开始生成周报统计...");

        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();

        Double avgScore = reportRepository.avgTotalScoreByDate(weekStart);
        Double avgPrecision = reportRepository.avgContextPrecisionByDate(weekStart);
        Double avgRecall = reportRepository.avgContextRecallByDate(weekStart);
        Double avgFaithfulness = reportRepository.avgFaithfulnessByDate(weekStart);
        Double avgRelevancy = reportRepository.avgAnswerRelevancyByDate(weekStart);
        long lowScoreCount = reportRepository.countLowScoreByDate(weekStart);
        long totalCount = reportRepository.countByDate(weekStart);

        log.info("=== RAG 评估周报 ===");
        log.info("评估样本数: {}", totalCount);
        log.info("平均总分: {}", avgScore != null ? String.format("%.1f", avgScore) : "N/A");
        log.info("上下文精度: {}", avgPrecision != null ? String.format("%.2f", avgPrecision) : "N/A");
        log.info("上下文召回: {}", avgRecall != null ? String.format("%.2f", avgRecall) : "N/A");
        log.info("忠实度: {}", avgFaithfulness != null ? String.format("%.2f", avgFaithfulness) : "N/A");
        log.info("答案相关性: {}", avgRelevancy != null ? String.format("%.2f", avgRelevancy) : "N/A");
        log.info("低分样本数: {}", lowScoreCount);
        log.info("低分率: {}", totalCount > 0 ? String.format("%.1f%%", lowScoreCount * 100.0 / totalCount) : "N/A");
        log.info("==================");
    }
}
