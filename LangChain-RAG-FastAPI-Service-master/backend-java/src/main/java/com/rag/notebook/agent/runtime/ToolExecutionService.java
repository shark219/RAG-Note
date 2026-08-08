package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentService;
import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.AgentTools;
import com.rag.notebook.agent.ToolResult;
import com.rag.notebook.agent.ToolStatus;
import com.rag.notebook.agent.tool.ToolRegistry;
import com.rag.notebook.agent.tool.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 统一的工具执行服务
 * 负责实际执行工具调用，处理超时、错误恢复
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolExecutionService {
    private final AgentTools agentTools;
    private final ToolRegistry toolRegistry;

    /**
     * 执行工具调用
     */
    public ToolExecutionResult execute(String toolName, String toolArgs,
                                       String userId, AgentState state) {
        long startTime = System.currentTimeMillis();

        try {
            ToolDefinition toolDef = toolRegistry.get(toolName).orElse(null);
            if (toolDef == null) {
                log.warn("未知工具: {}", toolName);
                return ToolExecutionResult.failure("未知工具: " + toolName, "UNKNOWN_TOOL",
                        System.currentTimeMillis() - startTime);
            }

            // 绑定状态并执行工具
            agentTools.bindState(state);

            // 解析参数并调用对应的工具方法
            Map<String, String> args = AgentService.parseToolArguments(toolArgs);
            String rawResult = executeToolByName(toolName, args, userId);

            // 获取结构化结果
            ToolResult toolResult = agentTools.getLastResult();

            long executionTime = System.currentTimeMillis() - startTime;

            if (toolResult != null && toolResult.status() == ToolStatus.ERROR) {
                return ToolExecutionResult.failure(rawResult, toolResult.errorCode(), executionTime);
            }

            return ToolExecutionResult.success(rawResult, toolResult, executionTime);

        } catch (Exception e) {
            log.error("工具执行异常: {}", toolName, e);
            long executionTime = System.currentTimeMillis() - startTime;
            return ToolExecutionResult.failure("工具执行异常: " + e.getMessage(),
                    "EXECUTION_ERROR", executionTime);
        } finally {
            agentTools.clearBoundState();
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
}
