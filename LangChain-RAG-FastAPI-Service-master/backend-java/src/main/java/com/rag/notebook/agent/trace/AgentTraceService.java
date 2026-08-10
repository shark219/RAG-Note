package com.rag.notebook.agent.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.Artifact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Agent Trace 记录服务
 * 负责在 Agent 执行过程中记录全链路追踪信息
 */
@Slf4j
@Service
public class AgentTraceService {

    private final AgentTraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    // ThreadLocal 存储当前追踪上下文
    private final ThreadLocal<AgentTrace> currentTrace = new ThreadLocal<>();
    private final ThreadLocal<List<ToolCallRecord>> currentToolCalls = new ThreadLocal<>();
    private final ThreadLocal<List<ErrorRecord>> currentErrors = new ThreadLocal<>();
    private final ThreadLocal<List<ReflectionRecord>> currentReflections = new ThreadLocal<>();
    private final ThreadLocal<List<String>> currentRagTraceIds = new ThreadLocal<>();
    private final ThreadLocal<List<LLMInteraction>> currentLLMInteractions = new ThreadLocal<>();

    public AgentTraceService(AgentTraceRepository traceRepository, ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 开始一个新的 trace
     */
    public void startTrace(String taskId, String sessionId, String userId,
                          String query, String modelName, Float temperature,
                          String systemPrompt) {
        AgentTrace trace = new AgentTrace();
        trace.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        trace.setTaskId(taskId);
        trace.setSessionId(sessionId);
        trace.setUserId(userId);
        trace.setQuery(query);
        trace.setModelName(modelName);
        trace.setTemperature(temperature);
        trace.setSystemPrompt(systemPrompt);
        trace.setStatus("RUNNING");
        trace.setLoopCount(0);
        trace.setToolCallCount(0);
        trace.setTotalTokens(0);

        currentTrace.set(trace);
        currentToolCalls.set(new ArrayList<>());
        currentErrors.set(new ArrayList<>());
        currentReflections.set(new ArrayList<>());
        currentRagTraceIds.set(new ArrayList<>());
        currentLLMInteractions.set(new ArrayList<>());

        log.debug("Started trace: traceId={}, taskId={}", trace.getTraceId(), taskId);
    }

    /**
     * 增加循环计数
     */
    public void incrementLoopCount() {
        AgentTrace trace = currentTrace.get();
        if (trace != null) {
            trace.setLoopCount(trace.getLoopCount() + 1);
        }
    }

    /**
     * 记录工具调用
     */
    public void recordToolCall(String toolName, String input, String output,
                               long latencyMs, boolean success, String errorMessage) {
        List<ToolCallRecord> toolCalls = currentToolCalls.get();
        if (toolCalls != null) {
            ToolCallRecord record = new ToolCallRecord();
            record.setStep(toolCalls.size() + 1);
            record.setToolName(toolName);
            record.setInput(truncate(input, 2000));
            record.setOutput(truncate(output, 2000));
            record.setLatencyMs(latencyMs);
            record.setSuccess(success);
            record.setErrorMessage(errorMessage);
            record.setTimestamp(System.currentTimeMillis());
            toolCalls.add(record);

            AgentTrace trace = currentTrace.get();
            if (trace != null) {
                trace.setToolCallCount(trace.getToolCallCount() + 1);
            }

            log.debug("Recorded tool call: tool={}, success=, latency={}ms", toolName, success, latencyMs);
        }
    }

    /**
     * 记录意图识别结果
     */
    public void recordIntentRecognition(String intent, List<String> selectedSkills,
                                       String reason, Map<String, Object> branchDecisions) {
        AgentTrace trace = currentTrace.get();
        if (trace != null) {
            Map<String, Object> intentData = new HashMap<>();
            intentData.put("intent", intent);
            intentData.put("selected_skills", selectedSkills);
            intentData.put("reason", reason);
            if (branchDecisions != null) {
                intentData.put("branch_decisions", branchDecisions);
            }
            trace.setIntentRecognition(intentData);
        }
    }

    /**
     * 记录规划信息
     */
    public void recordPlanning(String originalPlan, long planningLatencyMs) {
        AgentTrace trace = currentTrace.get();
        if (trace != null) {
            trace.setOriginalPlan(originalPlan);
            trace.setPlanningLatencyMs(planningLatencyMs);
        }
    }

    /**
     * 记录重规划事件
     */
    public void recordReplanning(String reason, String revisedPlan) {
        AgentTrace trace = currentTrace.get();
        if (trace != null) {
            trace.setReplanningTriggered(true);
            trace.setReplanningReason(reason);
            trace.setRevisedPlan(revisedPlan);
        }
    }

    /**
     * 记录反思结果
     */
    public void recordReflection(String summary, List<String> lessons,
                                Map<String, Object> adjustments) {
        List<ReflectionRecord> reflections = currentReflections.get();
        if (reflections != null) {
            ReflectionRecord record = new ReflectionRecord();
            record.setStep(reflections.size() + 1);
            record.setSummary(summary);
            record.setLessons(lessons);
            record.setAdjustments(adjustments);
            record.setTimestamp(System.currentTimeMillis());
            reflections.add(record);

            log.debug("Recorded reflection: step={}, summary={}", record.getStep(),
                    summary != null && summary.length() > 50 ? summary.substring(0, 50) + "..." : summary);
        }
    }

    /**
     * 记录 LLM 交互（可选，数据量大）
     */
    public void recordLLMInteraction(String prompt, String response, int inputTokens,
                                    int outputTokens, long latencyMs) {
        List<LLMInteraction> interactions = currentLLMInteractions.get();
        if (interactions != null && interactions.size() < 20) { // 限制记录数量
            LLMInteraction interaction = new LLMInteraction();
            interaction.setRound(interactions.size() + 1);
            interaction.setPrompt(truncate(prompt, 1000));
            interaction.setResponse(truncate(response, 1000));
            interaction.setInputTokens(inputTokens);
            interaction.setOutputTokens(outputTokens);
            interaction.setLatencyMs(latencyMs);
            interaction.setTimestamp(System.currentTimeMillis());
            interactions.add(interaction);
        }
    }

    /**
     * 记录关联的 RAG Trace ID
     */
    public void recordRagTraceId(String ragTraceId) {
        List<String> ragTraceIds = currentRagTraceIds.get();
        if (ragTraceIds != null && ragTraceId != null) {
            ragTraceIds.add(ragTraceId);
        }
    }

    /**
     * 记录错误
     */
    public void recordError(String errorType, String message, String toolName, String stackTrace) {
        List<ErrorRecord> errors = currentErrors.get();
        if (errors != null) {
            ErrorRecord record = new ErrorRecord();
            record.setErrorType(errorType);
            record.setMessage(message);
            record.setToolName(toolName);
            record.setStackTrace(truncate(stackTrace, 1000));
            record.setTimestamp(System.currentTimeMillis());
            errors.add(record);

            log.debug("Recorded error: type={}, tool={}, message={}", errorType, toolName, message);
        }
    }

    /**
     * 记录 Artifacts
     */
    public void recordArtifacts(List<Artifact> artifacts) {
        AgentTrace trace = currentTrace.get();
        if (trace != null && artifacts != null) {
            try {
                String artifactsJson = objectMapper.writeValueAsString(artifacts);
                trace.setArtifacts(artifactsJson);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize artifacts: {}", e.getMessage());
            }
        }
    }

    /**
     * 记录 Token 统计
     */
    public void recordTokenStats(int totalTokens, int systemPromptTokens,
                                 int historyTokens, int toolResultTokens) {
        AgentTrace trace = currentTrace.get();
        if (trace != null) {
            trace.setTotalTokens(totalTokens);
            trace.setSystemPromptTokens(systemPromptTokens);
            trace.setHistoryTokens(historyTokens);
            trace.setToolResultTokens(toolResultTokens);
        }
    }

    /**
     * 完成并保存 trace
     */
    @Transactional
    public String completeTrace(String finalAnswer, String status, long totalLatencyMs) {
        AgentTrace trace = currentTrace.get();
        if (trace == null) {
            log.warn("No active trace to complete");
            return null;
        }

        try {
            trace.setFinalAnswer(finalAnswer);
            trace.setStatus(status);
            trace.setTotalLatencyMs(totalLatencyMs);

            // 序列化工具调用记录
            List<ToolCallRecord> toolCalls = currentToolCalls.get();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                trace.setToolCalls(objectMapper.writeValueAsString(toolCalls));
            }

            // 序列化错误记录
            List<ErrorRecord> errors = currentErrors.get();
            if (errors != null && !errors.isEmpty()) {
                trace.setErrors(objectMapper.writeValueAsString(errors));
            }

            // 序列化反思记录
            List<ReflectionRecord> reflections = currentReflections.get();
            if (reflections != null && !reflections.isEmpty()) {
                trace.setReflectionRecords(objectMapper.writeValueAsString(reflections));
            }

            // 序列化 RAG Trace IDs
            List<String> ragTraceIds = currentRagTraceIds.get();
            if (ragTraceIds != null && !ragTraceIds.isEmpty()) {
                trace.setRagTraceIds(objectMapper.writeValueAsString(ragTraceIds));
            }

            // 序列化 LLM 交互记录（可选）
            List<LLMInteraction> llmInteractions = currentLLMInteractions.get();
            if (llmInteractions != null && !llmInteractions.isEmpty()) {
                trace.setLlmInteractions(objectMapper.writeValueAsString(llmInteractions));
            }

            traceRepository.save(trace);
            log.info("Saved trace: traceId={}, taskId={}, status={}, loops={}, toolCalls={}, latency={}ms",
                    trace.getTraceId(), trace.getTaskId(), status,
                    trace.getLoopCount(), trace.getToolCallCount(), totalLatencyMs);

            return trace.getTraceId();
        } catch (Exception e) {
            log.error("Failed to save trace: {}", e.getMessage(), e);
            return null;
        } finally {
            // 清理 ThreadLocal
            currentTrace.remove();
            currentToolCalls.remove();
            currentErrors.remove();
            currentReflections.remove();
            currentRagTraceIds.remove();
            currentLLMInteractions.remove();
        }
    }

    /**
     * 获取当前 trace ID
     */
    public String getCurrentTraceId() {
        AgentTrace trace = currentTrace.get();
        return trace != null ? trace.getTraceId() : null;
    }

    /**
     * 获取 trace 详情
     */
    public Optional<AgentTrace> getTrace(String traceId) {
        return traceRepository.findByTraceId(traceId);
    }

    /**
     * 获取任务的所有 traces
     */
    public List<AgentTrace> getTracesByTask(String taskId) {
        return traceRepository.findByTaskId(taskId);
    }

    /**
     * 获取用户的 traces
     */
    public List<AgentTrace> getTracesByUser(String userId, int limit) {
        List<AgentTrace> all = traceRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    // ========== 内部数据结构 ==========

    public static class ToolCallRecord {
        private int step;
        private String toolName;
        private String input;
        private String output;
        private long latencyMs;
        private boolean success;
        private String errorMessage;
        private long timestamp;

        // Getters and Setters
        public int getStep() { return step; }
        public void setStep(int step) { this.step = step; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }

    public static class ErrorRecord {
        private String errorType;
        private String message;
        private String toolName;
        private String stackTrace;
        private long timestamp;

        // Getters and Setters
        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getStackTrace() { return stackTrace; }
        public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }

    public static class ReflectionRecord {
        private int step;
        private String summary;
        private List<String> lessons;
        private Map<String, Object> adjustments;
        private long timestamp;

        // Getters and Setters
        public int getStep() { return step; }
        public void setStep(int step) { this.step = step; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<String> getLessons() { return lessons; }
        public void setLessons(List<String> lessons) { this.lessons = lessons; }
        public Map<String, Object> getAdjustments() { return adjustments; }
        public void setAdjustments(Map<String, Object> adjustments) { this.adjustments = adjustments; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }

    public static class LLMInteraction {
        private int round;
        private String prompt;
        private String response;
        private int inputTokens;
        private int outputTokens;
        private long latencyMs;
        private long timestamp;

        // Getters and Setters
        public int getRound() { return round; }
        public void setRound(int round) { this.round = round; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public String getResponse() { return response; }
        public void setResponse(String response) { this.response = response; }
        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}
