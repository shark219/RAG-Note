package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentState;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

public record WorkerLoopContext(
        String taskId,
        String stepId,
        String systemPrompt,
        String userQuery,
        String userId,
        String sessionId,
        AgentState agentState,
        List<ChatMessage> messages,
        List<ToolSpecification> activeTools,
        SseEmitter emitter,
        Integer maxIterations,
        Integer maxToolCalls,
        Integer maxTokens,
        String goal,
        List<String> successCriteria
) {
    public WorkerLoopContext {
        messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        activeTools = activeTools == null ? List.of() : List.copyOf(activeTools);
        maxIterations = maxIterations == null || maxIterations <= 0 ? 10 : maxIterations;
        maxToolCalls = maxToolCalls == null || maxToolCalls <= 0 ? 20 : maxToolCalls;
        maxTokens = maxTokens == null || maxTokens <= 0 ? 32000 : maxTokens;
    }
}
