package com.rag.notebook.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.agent.entity.AgentReflection;
import com.rag.notebook.agent.repo.AgentReflectionRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 反思服务（增强版）
 * 使用 LLM 深度分析执行状态，持久化反思结果
 */
@Slf4j
@Service
public class ReflectionService {

    private final ModelFactory modelFactory;
    private final AgentReflectionRepository reflectionRepository;
    private final ObjectMapper objectMapper;

    public ReflectionService(ModelFactory modelFactory,
                           AgentReflectionRepository reflectionRepository,
                           ObjectMapper objectMapper) {
        this.modelFactory = modelFactory;
        this.reflectionRepository = reflectionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行反思（使用 LLM 深度分析）
     */
    public ReflectionResult reflect(WorkerLoopContext context, String latestToolName, String latestRawResult) {
        AgentState state = context.agentState();
        if (state == null) {
            return ReflectionResult.noOp();
        }

        boolean shouldReflect = state.getConsecutiveNoProgress() >= 2 || hasRepeatedToolFailure(state);
        if (!shouldReflect) {
            return ReflectionResult.noOp();
        }

        try {
            // 使用 LLM 分析当前状态
            ReflectionResult result = reflectWithLLM(context, state, latestToolName, latestRawResult);

            // 持久化反思结果
            if (context.taskId() != null) {
                persistReflection(context, state, result);
            }

            return result;
        } catch (Exception e) {
            log.error("反思失败，回退到简单反思", e);
            return fallbackReflection(context, state, latestToolName, latestRawResult);
        }
    }

    /**
     * 使用 LLM 进行深度反思
     */
    private ReflectionResult reflectWithLLM(WorkerLoopContext context, AgentState state,
                                           String latestToolName, String latestRawResult) {
        ChatLanguageModel model = modelFactory.createChatModel(ModelFactory.TEMP_PRECISE);

        String systemPrompt = buildReflectionSystemPrompt();
        String userPrompt = buildReflectionUserPrompt(context, state, latestToolName, latestRawResult);

        String response = model.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
        ).content().text();

        return parseReflectionResponse(response, state);
    }

    /**
     * 构建反思系统提示
     */
    private String buildReflectionSystemPrompt() {
        return """
                你是 Agent 执行反思助手。分析当前任务执行状态，判断问题根因和改进建议。

                输出 JSON 格式（严格遵守，不要额外文字）：
                {
                  "goalAchieved": false,
                  "shouldReplan": true,
                  "shouldAskUser": false,
                  "failureType": "NO_PROGRESS | REPEATED_TOOL_FAILURE | MISSING_EVIDENCE | WRONG_STRATEGY",
                  "rootCause": "根本原因描述",
                  "missingEvidence": ["缺失证据1", "缺失证据2"],
                  "recommendedActions": ["建议行动1", "建议行动2"],
                  "summary": "反思总结",
                  "confidence": 0.75
                }
                """;
    }

    /**
     * 构建反思用户提示
     */
    private String buildReflectionUserPrompt(WorkerLoopContext context, AgentState state,
                                            String latestToolName, String latestRawResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("【任务目标】\n");
        sb.append(context.goal() != null ? context.goal() : context.userQuery()).append("\n\n");

        if (context.successCriteria() != null && !context.successCriteria().isEmpty()) {
            sb.append("【成功标准】\n");
            context.successCriteria().forEach(c -> sb.append("- ").append(c).append("\n"));
            sb.append("\n");
        }

        sb.append("【当前状态】\n");
        sb.append("- 连续无进展次数: ").append(state.getConsecutiveNoProgress()).append("\n");
        sb.append("- 工具调用次数: ").append(state.getToolHistory().size()).append("\n");
        sb.append("- 证据级别: ").append(state.getHighestEvidence()).append("\n");
        sb.append("- 已知事实数: ").append(state.getWorkingMemory().size()).append("\n");
        sb.append("- 失败操作数: ").append(state.getFailedActions().size()).append("\n\n");

        sb.append("【最近工具调用】\n");
        int historySize = state.getToolHistory().size();
        int startIdx = Math.max(0, historySize - 3);
        for (int i = startIdx; i < historySize; i++) {
            AgentState.ToolCallRecord record = state.getToolHistory().get(i);
            String resultPreview = record.result().length() > 100
                    ? record.result().substring(0, 100) + "..."
                    : record.result();
            sb.append(String.format("- %s: %s (质量: %s)\n",
                    record.toolName(), resultPreview, record.quality()));
        }

        if (latestToolName != null && latestRawResult != null) {
            sb.append("\n【最新工具结果】\n");
            sb.append("工具: ").append(latestToolName).append("\n");
            String preview = latestRawResult.length() > 200
                    ? latestRawResult.substring(0, 200) + "..."
                    : latestRawResult;
            sb.append("结果: ").append(preview).append("\n");
        }

        sb.append("\n请分析当前状态，输出反思结果 JSON。");
        return sb.toString();
    }

    /**
     * 解析 LLM 反思结果
     */
    private ReflectionResult parseReflectionResponse(String response, AgentState state) {
        try {
            // 提取 JSON（处理 markdown 代码块）
            String json = response.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            }
            if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            // 解析 JSON
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);

            boolean goalAchieved = node.has("goalAchieved") && node.get("goalAchieved").asBoolean();
            boolean shouldReplan = node.has("shouldReplan") && node.get("shouldReplan").asBoolean();
            boolean shouldAskUser = node.has("shouldAskUser") && node.get("shouldAskUser").asBoolean();
            String failureType = node.has("failureType") ? node.get("failureType").asText() : "UNKNOWN";
            String rootCause = node.has("rootCause") ? node.get("rootCause").asText() : "未知原因";
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.5;

            List<String> missingEvidence = new ArrayList<>();
            if (node.has("missingEvidence") && node.get("missingEvidence").isArray()) {
                node.get("missingEvidence").forEach(e -> missingEvidence.add(e.asText()));
            }

            List<String> recommendedActions = new ArrayList<>();
            if (node.has("recommendedActions") && node.get("recommendedActions").isArray()) {
                node.get("recommendedActions").forEach(a -> recommendedActions.add(a.asText()));
            }

            String summary = node.has("summary") ? node.get("summary").asText() : "反思完成";

            return new ReflectionResult(goalAchieved, shouldReplan, shouldAskUser,
                    failureType, rootCause, missingEvidence, recommendedActions, summary, confidence);

        } catch (JsonProcessingException e) {
            log.warn("解析反思结果失败，使用默认结果: {}", e.getMessage());
            return new ReflectionResult(false, true, false,
                    "PARSE_ERROR", "LLM 返回格式错误",
                    List.of(), List.of("切换策略"), "反思解析失败", 0.3);
        }
    }

    /**
     * 简单反思（回退方案）
     */
    private ReflectionResult fallbackReflection(WorkerLoopContext context, AgentState state,
                                               String latestToolName, String latestRawResult) {
        List<String> missingEvidence = new ArrayList<>();
        if (!state.hasGoodQualityResult()) {
            missingEvidence.add("缺少足够的高质量证据来支撑最终回答");
        }
        if (latestToolName != null && latestRawResult != null && latestRawResult.contains("找不到")) {
            missingEvidence.add("当前检索路径没有命中目标内容");
        }

        List<String> recommendedActions = new ArrayList<>();
        recommendedActions.add("切换检索词或改用其他工具路径");
        if (context.successCriteria() != null && !context.successCriteria().isEmpty()) {
            recommendedActions.add("对照 successCriteria 补齐缺失证据");
        }

        String failureType = state.getConsecutiveNoProgress() >= 2 ? "NO_PROGRESS" : "REPEATED_TOOL_FAILURE";
        String rootCause = missingEvidence.isEmpty() ? "当前执行路径未形成有效推进" : String.join("；", missingEvidence);
        String summary = "任务在当前路径上推进不足，需要切换策略";
        boolean shouldReplan = state.getConsecutiveNoProgress() >= 2;

        return new ReflectionResult(false, shouldReplan, false, failureType, rootCause,
                missingEvidence, recommendedActions, summary, 0.42);
    }

    /**
     * 持久化反思结果
     */
    private void persistReflection(WorkerLoopContext context, AgentState state, ReflectionResult result) {
        try {
            AgentReflection reflection = new AgentReflection();
            reflection.setId(UUID.randomUUID().toString().replace("-", ""));
            reflection.setTaskId(context.taskId());
            reflection.setStepId(context.stepId());
            reflection.setIteration(state.getIteration());
            reflection.setGoalAchieved(result.goalAchieved());
            reflection.setShouldReplan(result.shouldReplan());
            reflection.setShouldAskUser(result.shouldAskUser());
            reflection.setFailureType(result.failureType());
            reflection.setRootCause(result.rootCause());
            reflection.setMissingEvidenceJson(objectMapper.writeValueAsString(result.missingEvidence()));
            reflection.setRecommendedActionsJson(objectMapper.writeValueAsString(result.recommendedActions()));
            reflection.setSummary(result.summary());
            reflection.setConfidence(result.confidence());

            reflectionRepository.save(reflection);
            log.info("反思结果已持久化: taskId={}, iteration={}", context.taskId(), state.getIteration());
        } catch (JsonProcessingException e) {
            log.error("持久化反思结果失败", e);
        }
    }

    private boolean hasRepeatedToolFailure(AgentState state) {
        if (state.getToolHistory().size() < 2) {
            return false;
        }
        AgentState.ToolCallRecord last = state.getToolHistory().get(state.getToolHistory().size() - 1);
        AgentState.ToolCallRecord previous = state.getToolHistory().get(state.getToolHistory().size() - 2);
        return last.quality() == AgentState.ResultQuality.ERROR
                && previous.quality() == AgentState.ResultQuality.ERROR
                && last.toolName().equals(previous.toolName());
    }
}
