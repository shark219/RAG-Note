package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.ResultQuality;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 主循环：LLM 自主决策每一步，替代固定流水线
 *
 * 核心流程：思考 → 行动 → 观察 → 反思 → 决定下一步
 */
@Slf4j
@Component
public class AgentLoop {

    private static final int MAX_ITERATIONS = 10;

    private final AgentTools agentTools;
    private final ToolResultEvaluator evaluator;
    private final ModelFactory modelFactory;

    public AgentLoop(AgentTools agentTools, ToolResultEvaluator evaluator, ModelFactory modelFactory) {
        this.agentTools = agentTools;
        this.evaluator = evaluator;
        this.modelFactory = modelFactory;
    }

    /**
     * Agent 执行入口
     *
     * @param systemPrompt 系统提示词
     * @param userQuery    用户查询（可能包含附件上下文）
     * @param historyMessages 历史消息（已压缩）
     * @param userId       用户ID
     * @param activeTools  可用工具列表
     * @param emitter      SSE 发射器（可选，null 表示不需要流式输出）
     * @param forceToolHint Supervisor 建议的工具名（可选，不为空时第一轮未调用则强制执行）
     * @param forceToolDesc Supervisor 建议的工具描述（可选）
     * @return Agent 最终回答
     */
    public String run(String systemPrompt, String userQuery,
                      List<ChatMessage> historyMessages,
                      String userId, List<ToolSpecification> activeTools,
                      SseEmitter emitter,
                      String forceToolHint, String forceToolDesc) throws IOException {

        AgentState state = new AgentState(userQuery);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userQuery));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("Agent Loop 第 {} 轮", i + 1);

            ChatLanguageModel llm = modelFactory.createPreciseModel();
            Response<AiMessage> response = llm.generate(messages, activeTools);
            AiMessage aiMessage = response.content();

            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);

                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    String toolName = req.name();
                    String toolArgs = req.arguments();

                    sendSseEvent(emitter, "thinking", Map.of(
                            "stage", "tool_call",
                            "content", "正在调用工具: " + toolName
                    ));

                    // 执行工具
                    String result = executeTool(toolName, toolArgs, userId);
                    messages.add(ToolExecutionResultMessage.from(req, result));

                    log.info("工具 {} 结果 (前100字): {}", toolName,
                            result.length() > 100 ? result.substring(0, 100) + "..." : result);

                    // 反思：评估结果质量
                    ToolResultEvaluator.Evaluation eval = evaluator.evaluate(toolName, toolArgs, result, state.getOriginalQuery());
                    state.recordToolCall(toolName, toolArgs, result, eval.quality());

                    if (eval.quality() == ResultQuality.POOR) {
                        String reflection = evaluator.buildReflectionMessage(eval, toolName, toolArgs);
                        messages.add(SystemMessage.from(reflection));
                        log.info("反思: {}", reflection);
                    }
                }
            } else {
                // LLM 返回文本，未调用工具

                // 如果 Supervisor 明确建议了工具，且第一轮未调用，强制执行
                if (i == 0 && forceToolHint != null && !state.hasCalledTool(forceToolHint)) {
                    log.info("LLM 未调用 Supervisor 建议的工具 {}，强制执行", forceToolHint);
                    sendSseEvent(emitter, "thinking", Map.of(
                            "stage", "tool_call",
                            "content", "正在调用工具: " + forceToolHint
                    ));

                    String toolArgs = buildForceToolArgs(forceToolHint, forceToolDesc, userQuery);
                    String result = executeTool(forceToolHint, toolArgs, userId);
                    log.info("强制工具 {} 结果 (前100字): {}", forceToolHint,
                            result.length() > 100 ? result.substring(0, 100) + "..." : result);

                    // 用工具结果重新生成回答
                    messages.add(SystemMessage.from("工具查询结果：\n" + result
                            + "\n\n请根据以上工具查询结果回答用户的问题。"));
                    continue;  // 继续下一轮，让 LLM 基于工具结果生成回答
                }

                // LLM 认为任务完成
                String answer = aiMessage.text();
                state.setBestAnswer(answer);
                log.info("Agent Loop 完成，共 {} 轮", i + 1);
                return answer;
            }
        }

        // 达到最大轮次，强制生成最终回答
        log.info("Agent Loop 达到最大轮次 {}", MAX_ITERATIONS);
        ChatLanguageModel llm = modelFactory.createBalancedModel();
        Response<AiMessage> finalResponse = llm.generate(messages);
        String answer = finalResponse.content().text();
        state.setBestAnswer(answer);
        return answer;
    }

    /**
     * 构建强制工具调用的参数
     */
    private String buildForceToolArgs(String toolName, String toolDesc, String userQuery) {
        return switch (toolName) {
            case "createNote" -> {
                // 从用户查询和描述中提取标题和内容
                String title = toolDesc != null && !toolDesc.isBlank() ? toolDesc : userQuery;
                if (title.length() > 50) title = title.substring(0, 50);
                yield "{\"title\":\"" + escapeJson(title) + "\",\"content\":\"# " + escapeJson(title) + "\\n\\n（内容待补充）\"}";
            }
            case "searchNotes" -> "{\"query\":\"" + escapeJson(toolDesc != null ? toolDesc : userQuery) + "\"}";
            case "ragSummary" -> "{\"query\":\"" + escapeJson(toolDesc != null ? toolDesc : userQuery) + "\"}";
            case "editNote" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\",\"title\":\"\",\"content\":\"\"}";
            case "appendNote" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\",\"appendContent\":\"\"}";
            case "deleteNote" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\"}";
            case "getNoteStats" -> "{}";
            case "getTodayReviews" -> "{}";
            case "getRecentNotes" -> "{\"count\":\"3\"}";
            case "mergeNotes" -> "{\"noteIds\":\"" + escapeJson(toolDesc) + "\",\"newTitle\":\"合并笔记\"}";
            case "fetchUrl" -> "{\"url\":\"" + escapeJson(toolDesc) + "\"}";
            case "scheduleReview" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\",\"days\":\"3\"}";
            default -> "{}";
        };
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String executeTool(String toolName, String arguments, String userId) {
        try {
            Map<String, String> args = AgentService.parseToolArguments(arguments);
            return switch (toolName) {
                case "ragSummary" -> agentTools.ragSummary(args.getOrDefault("query", ""), userId);
                case "searchNotes" -> agentTools.searchNotes(args.getOrDefault("query", ""), userId);
                case "getNoteStats" -> agentTools.getNoteStats(userId);
                case "getRecentNotes" -> agentTools.getRecentNotes(
                        args.getOrDefault("count", "3"), userId);
                case "getTodayReviews" -> agentTools.getTodayReviews(userId);
                case "markReviewed" -> agentTools.markReviewed(args.getOrDefault("noteId", ""), userId);
                case "createNote" -> agentTools.createNote(
                        args.getOrDefault("title", ""), args.getOrDefault("content", ""), userId);
                case "editNote" -> agentTools.editNote(
                        args.getOrDefault("noteId", ""),
                        args.getOrDefault("title", ""),
                        args.getOrDefault("content", ""),
                        userId);
                case "appendNote" -> agentTools.appendNote(
                        args.getOrDefault("noteId", ""),
                        args.getOrDefault("appendContent", ""),
                        userId);
                case "deleteNote" -> agentTools.deleteNote(args.getOrDefault("noteId", ""), userId);
                case "getRelatedNotes" -> agentTools.getRelatedNotes(args.getOrDefault("noteId", ""), userId);
                case "mergeNotes" -> agentTools.mergeNotes(
                        args.getOrDefault("noteIds", ""),
                        args.getOrDefault("newTitle", "合并笔记"),
                        userId);
                case "fetchUrl" -> agentTools.fetchUrl(args.getOrDefault("url", ""));
                case "generateDiagram" -> agentTools.generateDiagram(
                        args.getOrDefault("type", "flowchart"),
                        args.getOrDefault("description", ""));
                case "scheduleReview" -> agentTools.scheduleReview(
                        args.getOrDefault("noteId", ""),
                        args.getOrDefault("days", "1"),
                        userId);
                case "whatTimeIsNow" -> agentTools.whatTimeIsNow();
                default -> "未知工具: " + toolName;
            };
        } catch (Exception e) {
            log.error("工具 '{}' 执行失败: {}", toolName, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    private void sendSseEvent(SseEmitter emitter, String type, Map<String, Object> data) throws IOException {
        if (emitter == null) return;
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("type", type);
        event.putAll(data);
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IllegalStateException e) {
            log.debug("SSE send skipped (emitter completed): type={}", type);
        } catch (IOException e) {
            log.debug("SSE send skipped (client disconnected): type={}, msg={}", type, e.getMessage());
        }
    }
}
