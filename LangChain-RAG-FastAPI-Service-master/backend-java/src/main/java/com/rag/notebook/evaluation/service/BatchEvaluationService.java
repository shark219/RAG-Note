package com.rag.notebook.evaluation.service;

import com.rag.notebook.evaluation.entity.EvaluationReport;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.repository.EvaluationReportRepository;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class BatchEvaluationService {

    private final RagTraceRepository traceRepository;
    private final EvaluationReportRepository reportRepository;
    private final EvaluationService evaluationService;

    public BatchEvaluationService(RagTraceRepository traceRepository,
                                  EvaluationReportRepository reportRepository,
                                  EvaluationService evaluationService) {
        this.traceRepository = traceRepository;
        this.reportRepository = reportRepository;
        this.evaluationService = evaluationService;
    }

    /**
     * 每天凌晨 2 点自动评估前一天的 Trace
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

        int evaluated = evaluationService.batchEvaluate(traces);

        log.info("每日评估完成: 总计 {} 条，成功评估 {} 条", traces.size(), evaluated);

        // 输出低分样本统计
        List<EvaluationReport> lowScores = reportRepository.findLowScoreReports();
        log.info("累计低分样本: {} 条", lowScores.size());
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
