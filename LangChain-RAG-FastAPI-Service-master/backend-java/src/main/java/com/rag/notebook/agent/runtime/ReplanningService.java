package com.rag.notebook.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.AgentLoop;
import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.agent.SubTask;
import com.rag.notebook.agent.SupervisorService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 重规划服务（增强版）
 * 基于反思结果动态调整计划，与 SupervisorService 集成
 */
@Slf4j
@Service
public class ReplanningService {

    private final ModelFactory modelFactory;
    private final SupervisorService supervisorService;
    private final ObjectMapper objectMapper;

    public ReplanningService(ModelFactory modelFactory,
                            SupervisorService supervisorService,
                            ObjectMapper objectMapper) {
        this.modelFactory = modelFactory;
        this.supervisorService = supervisorService;
        this.objectMapper = objectMapper;
    }

    /**
     * 重规划（使用 LLM 动态调整计划）
     */
    public ReplanResult replan(WorkerLoopContext context, ReflectionResult reflectionResult) {
        if (reflectionResult == null || !reflectionResult.shouldReplan()) {
            return ReplanResult.noOp();
        }

        try {
            // 发送重规划开始事件
            AgentLoop.emitThinking(context.emitter(), "replanning", "正在重新规划任务");

            // 使用 LLM 生成新计划
            List<SubTask> newSteps = replanWithLLM(context, reflectionResult);

            if (newSteps.isEmpty()) {
                log.warn("LLM 重规划返回空计划，使用回退方案");
                newSteps = fallbackReplan(context, reflectionResult);
            }

            // 发送重规划完成事件
            AgentLoop.emitThinking(context.emitter(), "replanned", "重规划完成，生成 " + newSteps.size() + " 个新步骤");

            return new ReplanResult(
                    reflectionResult.rootCause(),
                    newSteps,
                    true,
                    "已根据反思结果重新规划任务"
            );
        } catch (Exception e) {
            log.error("重规划失败，使用回退方案", e);
            List<SubTask> fallbackSteps = fallbackReplan(context, reflectionResult);
            return new ReplanResult(
                    reflectionResult.rootCause(),
                    fallbackSteps,
                    true,
                    "使用简化策略重新规划"
            );
        }
    }

    /**
     * 使用 LLM 重新规划
     */
    private List<SubTask> replanWithLLM(WorkerLoopContext context, ReflectionResult reflectionResult) {
        ChatLanguageModel model = modelFactory.createChatModel(ModelFactory.TEMP_PRECISE);

        String systemPrompt = buildReplanSystemPrompt();
        String userPrompt = buildReplanUserPrompt(context, reflectionResult);

        Response<AiMessage> response = model.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
        );

        // 统计 Token 消耗
        if (response.tokenUsage() != null) {
            context.agentState().addTokensConsumed(response.tokenUsage().totalTokenCount());
        }

        return parseReplanResponse(response.content().text());
    }

    /**
     * 构建重规划系统提示
     */
    private String buildReplanSystemPrompt() {
        return """
                你是 Agent 任务规划助手。根据反思结果重新规划任务步骤。

                输出 JSON 数组格式（严格遵守）：
                [
                  {
                    "label": "步骤名称",
                    "goal": "步骤目标",
                    "description": "步骤描述",
                    "toolHint": "fetchUrl | createNote | generateMindMap | appendNote | null",
                    "successCriteria": ["成功标准1", "成功标准2"]
                  }
                ]

                规划原则：
                1. 根据失败原因调整策略（切换工具、改变顺序、补充步骤）
                2. 保持步骤数量合理（1-4个）
                3. 每个步骤目标明确、可执行
                4. 优先使用推荐的工具提示
                """;
    }

    /**
     * 构建重规划用户提示
     */
    private String buildReplanUserPrompt(WorkerLoopContext context, ReflectionResult reflectionResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("【原始任务】\n");
        sb.append(context.userQuery()).append("\n\n");

        sb.append("【原计划目标】\n");
        sb.append(context.goal() != null ? context.goal() : "完成用户请求").append("\n\n");

        sb.append("【反思结果】\n");
        sb.append("- 失败类型: ").append(reflectionResult.failureType()).append("\n");
        sb.append("- 根本原因: ").append(reflectionResult.rootCause()).append("\n");

        if (!reflectionResult.missingEvidence().isEmpty()) {
            sb.append("- 缺失证据: \n");
            reflectionResult.missingEvidence().forEach(e -> sb.append("  * ").append(e).append("\n"));
        }

        if (!reflectionResult.recommendedActions().isEmpty()) {
            sb.append("- 推荐行动: \n");
            reflectionResult.recommendedActions().forEach(a -> sb.append("  * ").append(a).append("\n"));
        }
        sb.append("\n");

        sb.append("【当前状态】\n");
        AgentState state = context.agentState();
        if (state != null) {
            sb.append("- 工具调用次数: ").append(state.getToolHistory().size()).append("\n");
            sb.append("- 证据级别: ").append(state.getHighestEvidence()).append("\n");
            if (state.getSharedNoteId() != null) {
                sb.append("- 已创建笔记ID: ").append(state.getSharedNoteId()).append("\n");
            }
            if (state.isFetchStepFailed()) {
                sb.append("- 网页抓取已失败\n");
            }
        }

        sb.append("\n请根据反思结果重新规划任务步骤，输出 JSON 数组。");
        return sb.toString();
    }

    /**
     * 解析重规划响应
     */
    private List<SubTask> parseReplanResponse(String response) {
        try {
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

            List<SubTask> tasks = objectMapper.readValue(json, new TypeReference<List<SubTask>>() {});

            // 设置 ID 和执行模式
            for (int i = 0; i < tasks.size(); i++) {
                SubTask task = tasks.get(i);
                if (task.getId() == null) {
                    task.setId("RP-" + (i + 1));
                }
                if (task.getExecutionMode() == null) {
                    task.setExecutionMode("SEQUENTIAL");
                }
            }

            return tasks;
        } catch (JsonProcessingException e) {
            log.warn("解析重规划结果失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 回退重规划方案（简化版）
     */
    private List<SubTask> fallbackReplan(WorkerLoopContext context, ReflectionResult reflectionResult) {
        List<SubTask> newSteps = new ArrayList<>();

        // 根据失败类型生成简化计划
        String failureType = reflectionResult.failureType();
        AgentState state = context.agentState();

        // URL 白名单限制 → 切换到搜索本地笔记
        if ("URL_BLOCKED".equals(failureType) || isUrlBlockedByPolicy(reflectionResult, state)) {
            SubTask step = new SubTask();
            step.setId("RP-1");
            step.setLabel("在本地笔记中搜索相关内容");
            step.setGoal("从已有笔记中查找相关信息");
            step.setDescription("由于网页访问受限，改为搜索本地笔记库中的相关内容");
            step.setToolHint("searchNotes,ragSummary");
            step.setSuccessCriteria(List.of("找到相关笔记", "总结核心内容"));
            step.setExecutionMode("SEQUENTIAL");
            newSteps.add(step);

            log.info("重规划: URL 访问受限 → 切换到本地笔记搜索");
            return newSteps;
        }

        // fetchUrl 连续失败 → 直接报告失败，不要继续
        if (state != null && state.isFetchStepFailed() &&
            state.getFailedActions().stream().anyMatch(a -> a.contains("fetchUrl"))) {
            SubTask step = new SubTask();
            step.setId("RP-1");
            step.setLabel("报告网页抓取失败");
            step.setGoal("向用户说明无法获取网页内容的原因");
            step.setDescription("无法访问指定网页，向用户报告原因并询问是否切换策略");
            step.setToolHint("NONE"); // 明确标记不需要调用工具
            step.setSuccessCriteria(List.of("说明失败原因", "提供替代方案"));
            step.setExecutionMode("SEQUENTIAL");
            newSteps.add(step);

            log.info("重规划: fetchUrl 失败 → 报告失败并询问用户");
            return newSteps;
        }

        if ("NO_PROGRESS".equals(failureType) || "REPEATED_TOOL_FAILURE".equals(failureType)) {
            // 切换策略
            SubTask step = new SubTask();
            step.setId("RP-1");
            step.setLabel("切换执行策略");
            step.setGoal(context.goal() != null ? context.goal() : context.userQuery());
            step.setDescription("尝试使用不同的工具或方法完成任务");
            step.setSuccessCriteria(context.successCriteria());
            step.setExecutionMode("SEQUENTIAL");
            newSteps.add(step);
        } else if ("MISSING_EVIDENCE".equals(failureType)) {
            // 补充证据
            SubTask step = new SubTask();
            step.setId("RP-1");
            step.setLabel("补充缺失证据");
            step.setGoal("获取支撑目标所需的证据和信息");
            step.setDescription(reflectionResult.rootCause());
            step.setSuccessCriteria(reflectionResult.missingEvidence());
            step.setExecutionMode("SEQUENTIAL");
            newSteps.add(step);
        } else {
            // 通用重试
            SubTask step = new SubTask();
            step.setId("RP-1");
            step.setLabel("重新执行");
            step.setGoal(context.goal() != null ? context.goal() : context.userQuery());
            step.setDescription(reflectionResult.summary());
            step.setSuccessCriteria(context.successCriteria());
            step.setExecutionMode("SEQUENTIAL");
            newSteps.add(step);
        }

        return newSteps;
    }

    /**
     * 判断是否为 URL 白名单阻止导致的失败
     */
    private boolean isUrlBlockedByPolicy(ReflectionResult reflectionResult, AgentState state) {
        if (reflectionResult == null || state == null) return false;

        String rootCause = reflectionResult.rootCause();
        boolean hasUrlBlockedMessage = rootCause != null &&
            (rootCause.contains("白名单") || rootCause.contains("不在允许") ||
             rootCause.contains("访问受限") || rootCause.contains("URL被拦截"));

        boolean hasFailedFetchUrl = state.getFailedActions().stream()
            .anyMatch(a -> a.contains("fetchUrl") &&
                          (a.contains("白名单") || a.contains("不在允许")));

        return hasUrlBlockedMessage || hasFailedFetchUrl;
    }
}
