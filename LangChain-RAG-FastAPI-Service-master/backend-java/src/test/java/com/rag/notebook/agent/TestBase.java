package com.rag.notebook.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试基类：提供通用的 LLM Mock 工具
 */
public class TestBase {

    /**
     * 创建返回固定文本的 ChatLanguageModel Mock
     */
    @SuppressWarnings("unchecked")
    protected ChatLanguageModel mockLlm(String fixedResponse) {
        ChatLanguageModel llm = mock(ChatLanguageModel.class);
        AiMessage aiMessage = AiMessage.from(fixedResponse);
        Response<AiMessage> response = Response.from(aiMessage);
        when(llm.generate(any(List.class))).thenReturn(response);
        when(llm.generate(any(List.class), any(List.class))).thenReturn(response);
        return llm;
    }

    /**
     * 创建按顺序返回不同响应的 ChatLanguageModel Mock
     */
    @SuppressWarnings("unchecked")
    protected ChatLanguageModel mockLlmSequential(String... responses) {
        ChatLanguageModel llm = mock(ChatLanguageModel.class);
        Response<AiMessage>[] responseArray = new Response[responses.length];
        for (int i = 0; i < responses.length; i++) {
            responseArray[i] = Response.from(AiMessage.from(responses[i]));
        }
        var stubbing = when(llm.generate(any(List.class)));
        for (int i = 0; i < responseArray.length; i++) {
            if (i == 0) {
                stubbing.thenReturn(responseArray[i]);
            } else {
                stubbing = stubbing.thenReturn(responseArray[i]);
            }
        }
        return llm;
    }

    /**
     * 创建 ModelFactory Mock，返回指定的 ChatLanguageModel
     */
    protected ModelFactory mockModelFactory(ChatLanguageModel llm) {
        ModelFactory factory = mock(ModelFactory.class);
        when(factory.createChatModel()).thenReturn(llm);
        when(factory.createChatModel(any(Double.class))).thenReturn(llm);
        when(factory.createPreciseModel()).thenReturn(llm);
        when(factory.createBalancedModel()).thenReturn(llm);
        when(factory.createCreativeModel()).thenReturn(llm);
        return factory;
    }

    /**
     * 构造 Clarifier 返回的 JSON 响应
     */
    protected String clarifyClearResponse(String brief) {
        return "{\"is_clear\":true,\"research_brief\":\"" + brief + "\"}";
    }

    /**
     * 构造 Clarifier 返回模糊方向的 JSON 响应
     */
    protected String clarifyUnclearResponse(String message, List<String> directions) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"is_clear\":false,\"message\":\"").append(message).append("\",\"suggested_directions\":[");
        for (int i = 0; i < directions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(directions.get(i)).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 构造 Supervisor 返回子任务的 JSON 响应
     */
    protected String supervisorPlanResponse(List<String[]> tasks) {
        // tasks: [[label, description, tool], ...]
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tasks.size(); i++) {
            if (i > 0) sb.append(",");
            String[] t = tasks.get(i);
            sb.append("{\"label\":\"").append(t[0]).append("\",")
              .append("\"description\":\"").append(t[1]).append("\",")
              .append("\"tool\":\"").append(t[2]).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 构造 QualityReviewer 审查通过的 JSON 响应
     */
    protected String reviewApprovedResponse() {
        return "{\"verdict\":\"approved\",\"reason\":\"回答质量良好\"}";
    }

    /**
     * 构造 QualityReviewer 审查不通过的 JSON 响应
     */
    protected String reviewRetryResponse(String feedback) {
        return "{\"verdict\":\"retry\",\"reason\":\"回答不够完整\",\"feedback\":\"" + feedback + "\"}";
    }
}
