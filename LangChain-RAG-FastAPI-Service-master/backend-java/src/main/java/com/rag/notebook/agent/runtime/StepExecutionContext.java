package com.rag.notebook.agent.runtime;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public record StepExecutionContext(
        String taskId,
        String stepId,
        String systemPrompt,
        String userQuery,
        String userId,
        String sessionId,
        List<ChatMessage> historyMessages,
        List<ToolSpecification> activeTools,
        SseEmitter emitter,
        String goal,
        List<String> successCriteria,
        List<String> allowedToolNames,
        String taskType,
        boolean reuseSharedState
) {
}
