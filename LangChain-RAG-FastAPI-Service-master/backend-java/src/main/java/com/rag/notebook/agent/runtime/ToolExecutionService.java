package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentService;
import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.AgentTools;
import com.rag.notebook.agent.ToolResult;
import com.rag.notebook.agent.ToolStatus;
import com.rag.notebook.agent.tool.ToolRegistry;
import com.rag.notebook.agent.tool.ToolDefinition;
import com.rag.notebook.agent.entity.AgentToolMetric;
import com.rag.notebook.agent.repo.AgentToolMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 统一的工具执行服务
 * 负责实际执行工具调用，处理超时、错误恢复、指标采集
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolExecutionService {
    private final AgentTools agentTools;
    private final ToolRegistry toolRegistry;
    private final AgentToolMetricRepository toolMetricRepository;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * 执行工具调用（带指标采集）
     */
    public ToolExecutionResult execute(String toolName, String toolArgs,
                                       String userId, AgentState state) {
        return execute(toolName, toolArgs, userId, null, null, state);
    }

    /**
     * 执行工具调用（完整版，支持 taskId 和 sessionId）
     */
    public ToolExecutionResult execute(String toolName, String toolArgs,
                                       String userId, String taskId, String sessionId,
                                       AgentState state) {
        long startTime = System.currentTimeMillis();
        boolean success = false;
        String errorCode = null;
        String errorMessage = null;

        try {
            ToolDefinition toolDef = toolRegistry.get(toolName).orElse(null);
            if (toolDef == null) {
                log.warn("未知工具: {}", toolName);
                errorCode = "UNKNOWN_TOOL";
                errorMessage = "未知工具: " + toolName;
                return ToolExecutionResult.failure(errorMessage, errorCode,
                        System.currentTimeMillis() - startTime);
            }

            // 获取超时配置（秒转毫秒）
            int timeoutMs = toolDef.getTimeoutSeconds() * 1000;

            // 使用 Future 实现超时控制
            Future<ExecutionHolder> future = executorService.submit(() -> {
                try {
                    // 绑定状态并执行工具
                    agentTools.bindState(state);

                    // 解析参数并调用对应的工具方法
                    Map<String, String> args = AgentService.parseToolArguments(toolArgs);
                    String rawResult = executeToolByName(toolName, args, userId);

                    // 获取结构化结果
                    ToolResult toolResult = agentTools.getLastResult();

                    return new ExecutionHolder(rawResult, toolResult);
                } finally {
                    agentTools.clearBoundState();
                }
            });

            ExecutionHolder holder;
            try {
                // 等待执行完成，带超时控制
                holder = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                errorCode = "TIMEOUT";
                errorMessage = String.format("工具执行超时（>%ds）", toolDef.getTimeoutSeconds());
                log.warn("工具执行超时: tool={}, timeout={}ms", toolName, timeoutMs);
                return ToolExecutionResult.failure(errorMessage, errorCode,
                        System.currentTimeMillis() - startTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorCode = "INTERRUPTED";
                errorMessage = "工具执行被中断";
                log.warn("工具执行被中断: tool={}", toolName);
                return ToolExecutionResult.failure(errorMessage, errorCode,
                        System.currentTimeMillis() - startTime);
            } catch (ExecutionException e) {
                errorCode = "EXECUTION_ERROR";
                errorMessage = "工具执行异常: " + e.getCause().getMessage();
                log.error("工具执行异常: tool={}", toolName, e.getCause());
                return ToolExecutionResult.failure(errorMessage, errorCode,
                        System.currentTimeMillis() - startTime);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            if (holder.toolResult != null && holder.toolResult.status() == ToolStatus.ERROR) {
                success = false;
                errorCode = holder.toolResult.errorCode();
                errorMessage = holder.rawResult;
                return ToolExecutionResult.failure(holder.rawResult, holder.toolResult.errorCode(), executionTime);
            }

            success = true;
            return ToolExecutionResult.success(holder.rawResult, holder.toolResult, executionTime);

        } catch (Exception e) {
            log.error("工具执行异常: {}", toolName, e);
            long executionTime = System.currentTimeMillis() - startTime;
            errorCode = "EXECUTION_ERROR";
            errorMessage = "工具执行异常: " + e.getMessage();
            return ToolExecutionResult.failure(errorMessage, errorCode, executionTime);
        } finally {
            // 采集工具执行指标
            long executionTime = System.currentTimeMillis() - startTime;
            recordMetric(toolName, success, executionTime, errorCode, errorMessage, userId, taskId, sessionId);
        }
    }

    private String executeToolByName(String toolName, Map<String, String> args, String userId) {
        return switch (toolName) {
            case "listNotes" -> agentTools.listNotes(args.getOrDefault("count", "20"), args.getOrDefault("category", ""), userId);
            case "getNote" -> agentTools.getNote(args.getOrDefault("noteId", ""), userId);
            case "ragSummary" -> agentTools.ragSummary(args.getOrDefault("query", ""), userId);
            case "searchNotes" -> agentTools.searchNotes(args.getOrDefault("query", ""), userId);
            case "getNoteStats" -> agentTools.getNoteStats(userId);
            case "getRecentNotes" -> agentTools.getRecentNotes(args.getOrDefault("count", "3"), userId);
            case "getTodayReviews" -> agentTools.getTodayReviews(userId);
            case "markReviewed" -> agentTools.markReviewed(args.getOrDefault("noteId", ""), userId);
            case "createNote" -> agentTools.createNote(args.getOrDefault("title", ""), args.getOrDefault("content", ""), userId);
            case "editNote" -> agentTools.editNote(args.getOrDefault("noteId", ""), args.getOrDefault("title", ""), args.getOrDefault("content", ""), userId);
            case "appendNote" -> agentTools.appendNote(args.getOrDefault("noteId", ""), args.getOrDefault("appendContent", ""), userId);
            case "deleteNote" -> agentTools.deleteNote(args.getOrDefault("noteId", ""), userId);
            case "getRelatedNotes" -> agentTools.getRelatedNotes(args.getOrDefault("noteId", ""), userId);
            case "mergeNotes" -> agentTools.mergeNotes(args.getOrDefault("noteIds", ""), args.getOrDefault("newTitle", "合并笔记"), userId);
            case "fetchUrl" -> agentTools.fetchUrl(args.getOrDefault("url", ""));
            case "generateDiagram" -> agentTools.generateDiagram(args.getOrDefault("type", "flowchart"), args.getOrDefault("description", ""));
            case "scheduleReview" -> agentTools.scheduleReview(args.getOrDefault("noteId", ""), args.getOrDefault("days", "1"), userId);
            case "generateMindMap" -> agentTools.generateMindMap(args.getOrDefault("noteId", ""), userId);
            case "whatTimeIsNow" -> agentTools.whatTimeIsNow();
            default -> "未知工具: " + toolName;
        };
    }

    /**
     * 记录工具执行指标
     */
    private void recordMetric(String toolName, boolean success, long latencyMs,
                             String errorCode, String errorMessage,
                             String userId, String taskId, String sessionId) {
        try {
            AgentToolMetric metric = new AgentToolMetric();
            metric.setId(UUID.randomUUID().toString().replace("-", ""));
            metric.setTaskId(taskId);
            metric.setToolName(toolName);
            metric.setSuccess(success);
            metric.setLatencyMs(latencyMs);
            metric.setErrorCode(errorCode);
            metric.setErrorMessage(errorMessage);
            metric.setUserId(userId);
            metric.setSessionId(sessionId);

            toolMetricRepository.save(metric);
            log.debug("工具指标已记录: tool={}, success={}, latency={}ms", toolName, success, latencyMs);
        } catch (Exception e) {
            log.error("记录工具指标失败: tool={}", toolName, e);
        }
    }

    /**
     * 工具执行结果持有者（内部类）
     */
    private static class ExecutionHolder {
        final String rawResult;
        final ToolResult toolResult;

        ExecutionHolder(String rawResult, ToolResult toolResult) {
            this.rawResult = rawResult;
            this.toolResult = toolResult;
        }
    }
}
