package com.rag.notebook.agent.metrics;

import com.rag.notebook.agent.repo.AgentReflectionRepository;
import com.rag.notebook.agent.repo.AgentTaskRepository;
import com.rag.notebook.agent.repo.AgentToolMetricRepository;
import com.rag.notebook.agent.runtime.AgentTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Agent 评估指标统计服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMetricsService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AgentTaskRepository taskRepository;
    private final AgentReflectionRepository reflectionRepository;
    private final AgentToolMetricRepository toolMetricRepository;

    /**
     * 获取指定日期范围的 Agent 评估指标
     */
    public AgentMetrics getMetrics(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        AgentMetrics metrics = new AgentMetrics();
        metrics.setStartDate(startDate.format(DATE_FORMATTER));
        metrics.setEndDate(endDate.format(DATE_FORMATTER));

        // 任务相关指标
        calculateTaskMetrics(metrics, startDateTime, endDateTime);

        // 反思相关指标
        calculateReflectionMetrics(metrics, startDateTime, endDateTime);

        // 工具执行指标
        calculateToolMetrics(metrics, startDateTime, endDateTime);

        return metrics;
    }

    /**
     * 获取最近 N 天的指标
     */
    public AgentMetrics getRecentMetrics(int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);
        return getMetrics(startDate, endDate);
    }

    /**
     * 计算任务相关指标
     */
    private void calculateTaskMetrics(AgentMetrics metrics, LocalDateTime start, LocalDateTime end) {
        long totalTasks = taskRepository.countByStartedAtBetween(start, end);
        long completedTasks = taskRepository.countByStatusAndStartedAtBetween(
                AgentTaskStatus.COMPLETED.name(), start, end);
        long failedTasks = taskRepository.countByStatusAndStartedAtBetween(
                AgentTaskStatus.FAILED.name(), start, end);
        long blockedTasks = taskRepository.countByStatusAndStartedAtBetween(
                AgentTaskStatus.BLOCKED.name(), start, end);

        metrics.setTotalTasks(totalTasks);
        metrics.setCompletedTasks(completedTasks);
        metrics.setFailedTasks(failedTasks);
        metrics.setBlockedTasks(blockedTasks);

        // 计算完成率
        if (totalTasks > 0) {
            metrics.setCompletionRate((double) completedTasks / totalTasks * 100);
        } else {
            metrics.setCompletionRate(0.0);
        }

        // 平均迭代轮次
        Double avgIterations = taskRepository.avgIterationCountByStartedAtBetween(start, end);
        metrics.setAvgIterationCount(avgIterations != null ? avgIterations : 0.0);

        // 平均工具调用次数
        Double avgToolCalls = taskRepository.avgToolCallCountByStartedAtBetween(start, end);
        metrics.setAvgToolCallCount(avgToolCalls != null ? avgToolCalls : 0.0);

        // 平均 Token 消耗
        Double avgTokens = taskRepository.avgTokenConsumedByStartedAtBetween(start, end);
        metrics.setAvgTokenConsumed(avgTokens != null ? avgTokens : 0.0);
    }

    /**
     * 计算反思相关指标
     */
    private void calculateReflectionMetrics(AgentMetrics metrics, LocalDateTime start, LocalDateTime end) {
        long reflectionCount = reflectionRepository.countByCreatedAtBetween(start, end);
        metrics.setReflectionCount(reflectionCount);

        long replanCount = reflectionRepository.countByShouldReplanAndCreatedAtBetween(true, start, end);
        metrics.setReplanCount(replanCount);

        long urlBlockedCount = reflectionRepository.countByFailureTypeAndCreatedAtBetween(
                "URL_BLOCKED", start, end);
        metrics.setUrlBlockedCount(urlBlockedCount);
    }

    /**
     * 计算工具执行指标
     */
    private void calculateToolMetrics(AgentMetrics metrics, LocalDateTime start, LocalDateTime end) {
        long totalToolExecutions = toolMetricRepository.countByCreatedAtBetween(start, end);
        long successfulToolExecutions = toolMetricRepository.countBySuccessAndCreatedAtBetween(
                true, start, end);

        metrics.setTotalToolExecutions(totalToolExecutions);
        metrics.setSuccessfulToolExecutions(successfulToolExecutions);

        // 计算工具成功率
        if (totalToolExecutions > 0) {
            metrics.setToolSuccessRate((double) successfulToolExecutions / totalToolExecutions * 100);
        } else {
            metrics.setToolSuccessRate(0.0);
        }

        // 平均工具耗时
        Double avgLatency = toolMetricRepository.avgLatencyMsByCreatedAtBetween(start, end);
        metrics.setAvgToolLatencyMs(avgLatency != null ? avgLatency : 0.0);
    }
}
