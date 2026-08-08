package com.rag.notebook.agent;

import com.rag.notebook.agent.runtime.AgentRuntime;
import com.rag.notebook.agent.runtime.AgentTaskService;
import com.rag.notebook.cache.QueryCacheService;
import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import com.rag.notebook.rag.QualityReviewer;
import com.rag.notebook.skill.service.SkillContextResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 工具调用链路测试
 * 覆盖：工具调用顺序、多工具串联、参数解析、异常容错、失败重试
 */
@DisplayName("工具调用链路测试")
@Disabled("executeToolWithUserId 方法已移除，需要重构测试")
class ToolChainTest extends TestBase {

    private AgentTools agentTools;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentTools = mock(AgentTools.class);
        when(agentTools.ragSummary(any(), any())).thenReturn("RAG 检索结果：BM25 是一种排序算法");
        when(agentTools.searchNotes(any(), any())).thenReturn("找到 3 条相关笔记");
        when(agentTools.getNoteStats(any())).thenReturn("笔记统计：总计 10 条");
        when(agentTools.getTodayReviews(any())).thenReturn("今天需要复习 2 条笔记");
        when(agentTools.createNote(any(), any(), any())).thenReturn("笔记创建成功，ID: 123");
        when(agentTools.getRelatedNotes(any(), any())).thenReturn("找到 2 条相关笔记");
        when(agentTools.whatTimeIsNow()).thenReturn("2026-07-15 10:30");
        when(agentTools.getLatestTraceId()).thenReturn(null);
    }

    private AgentService buildAgentService(ChatLanguageModel llm) {
        ModelFactory modelFactory = mockModelFactory(llm);
        ChatService chatService = mock(ChatService.class);
        when(chatService.getSessionMessages(any())).thenReturn(List.of());
        ApplicationProperties props = mock(ApplicationProperties.class);
        Executor executor = Runnable::run; // 同步执行
        ContextManager contextManager = mock(ContextManager.class);
        when(contextManager.buildMessages(any(), any())).thenReturn(List.of());
        RagTraceRepository traceRepository = mock(RagTraceRepository.class);
        QualityReviewer qualityReviewer = mock(QualityReviewer.class);
        when(qualityReviewer.reviewAnswer(any(), any(), any()))
                .thenReturn(new QualityReviewer.ReviewResult(true, "通过", null));
        SupervisorService supervisorService = mock(SupervisorService.class);
        when(supervisorService.plan(any())).thenReturn(List.of());
        WriterService writerService = mock(WriterService.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ConversationContextManager convCtxMgr = mock(ConversationContextManager.class);
        SkillContextResolver skillCtx = mock(SkillContextResolver.class);
        when(skillCtx.resolve(any())).thenReturn(new SkillContextResolver.Context("", java.util.Set.of(), false, "v1"));
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        AgentTaskService agentTaskService = mock(AgentTaskService.class);

        return new AgentService(modelFactory, agentTools, chatService, props,
                executor, contextManager, traceRepository, qualityReviewer,
                supervisorService, writerService, tokenCounter, agentLoop,
                responseComposer, convCtxMgr, agentRuntime, agentTaskService, skillCtx);
    }

    @Nested
    @DisplayName("工具调用顺序")
    class ToolCallOrder {

        @Test
        @DisplayName("单工具调用：searchNotes → 返回结果")
        void singleToolCall_shouldReturnResult() {
            // 模拟 LLM 先请求工具调用，再返回文本
            String toolCallJson = "{\"query\":\"BM25\"}";
            ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                    .name("searchNotes").arguments(toolCallJson).id("call_1").build();
            AiMessage toolCallMsg = AiMessage.from(List.of(toolRequest));
            AiMessage finalMsg = AiMessage.from("找到关于 BM25 的笔记。");

            ChatLanguageModel llm = mock(ChatLanguageModel.class);
            when(llm.generate(any(List.class), any(List.class)))
                    .thenReturn(Response.from(toolCallMsg))
                    .thenReturn(Response.from(finalMsg));
            when(llm.generate(any(List.class))).thenReturn(Response.from(finalMsg));

            agentService = buildAgentService(llm);
            SseEmitter emitter = new SseEmitter();

            // 调用会触发工具调用
            // 注意：这里测试的是 executeToolWithUserId 的路由逻辑
            String result = invokeExecuteToolWithUserId("searchNotes", toolCallJson, "user1");

            assertTrue(result.contains("相关笔记"));
            verify(agentTools).searchNotes("BM25", "user1");
        }

        @Test
        @DisplayName("多工具串联：searchNotes + ragSummary 应按顺序调用")
        void sequentialToolCalls_shouldCallInOrder() {
            agentService = buildAgentService(mockLlm("测试"));

            // 模拟两轮工具调用
            invokeExecuteToolWithUserId("searchNotes", "{\"query\":\"AI\"}", "user1");
            invokeExecuteToolWithUserId("ragSummary", "{\"query\":\"AI深度学习\"}", "user1");

            var order = inOrder(agentTools);
            order.verify(agentTools).searchNotes("AI", "user1");
            order.verify(agentTools).ragSummary("AI深度学习", "user1");
        }
    }

    @Nested
    @DisplayName("参数解析与容错")
    class ParameterParsing {

        @Test
        @DisplayName("正常 JSON 参数应正确解析")
        void normalJsonParams_shouldParseCorrectly() {
            agentService = buildAgentService(mockLlm("测试"));

            String result = invokeExecuteToolWithUserId(
                    "createNote",
                    "{\"title\":\"测试笔记\",\"content\":\"这是内容\"}",
                    "user1"
            );

            assertTrue(result.contains("创建成功"));
            verify(agentTools).createNote("测试笔记", "这是内容", "user1");
        }

        @Test
        @DisplayName("空参数应使用默认值")
        void emptyParams_shouldUseDefaults() {
            agentService = buildAgentService(mockLlm("测试"));

            String result = invokeExecuteToolWithUserId("searchNotes", "{}", "user1");

            // 应该用空字符串作为 query
            verify(agentTools).searchNotes("", "user1");
        }

        @Test
        @DisplayName("未知工具名应返回错误信息")
        void unknownTool_shouldReturnError() {
            agentService = buildAgentService(mockLlm("测试"));

            String result = invokeExecuteToolWithUserId("unknownTool", "{}", "user1");

            assertTrue(result.contains("未知工具"));
        }
    }

    @Nested
    @DisplayName("异常处理与降级")
    class ErrorHandling {

        @Test
        @DisplayName("工具执行异常应返回错误信息而非崩溃")
        void toolException_shouldReturnErrorMessage() {
            when(agentTools.searchNotes(any(), any())).thenThrow(new RuntimeException("数据库连接失败"));
            agentService = buildAgentService(mockLlm("测试"));

            String result = invokeExecuteToolWithUserId("searchNotes", "{\"query\":\"test\"}", "user1");

            assertTrue(result.contains("工具执行失败") || result.contains("数据库连接失败"));
        }

        @Test
        @DisplayName("null 参数 JSON 应安全处理")
        void nullArgsJson_shouldHandleSafely() {
            agentService = buildAgentService(mockLlm("测试"));

            String result = invokeExecuteToolWithUserId("searchNotes", null, "user1");

            // 不应抛异常
            assertNotNull(result);
        }

        @Test
        @DisplayName("畸形 JSON 参数应安全处理")
        void malformedJson_shouldHandleSafely() {
            agentService = buildAgentService(mockLlm("测试"));

            String result = invokeExecuteToolWithUserId("searchNotes", "{invalid json}", "user1");

            assertNotNull(result);
        }
    }

    /**
     * 通过反射调用私有方法 executeToolWithUserId
     */
    private String invokeExecuteToolWithUserId(String toolName, String args, String userId) {
        try {
            var method = AgentService.class.getDeclaredMethod(
                    "executeToolWithUserId", String.class, String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(agentService, toolName, args, userId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
            return null;
        }
    }
}
