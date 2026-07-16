package com.rag.notebook.agent;

import com.rag.notebook.chat.dto.ClarifyResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 意图识别测试
 * 覆盖：模糊提问、多意图混合、歧义问句、专业术语、反向诱导
 */
@DisplayName("意图识别测试")
class IntentRecognitionTest extends TestBase {

    private ClarifierService clarifierService;
    private SupervisorService supervisorService;

    @Nested
    @DisplayName("Clarifier 查询清晰度判断")
    class ClarifierTests {

        @Test
        @DisplayName("模糊提问：'帮我学习AI' → 应返回不清晰 + 方向建议")
        void fuzzyQuery_shouldReturnUnclear() {
            String response = clarifyUnclearResponse(
                    "AI 涵盖的内容比较广，以下方向你可以看看：",
                    Arrays.asList("AI 基础概念与核心算法", "机器学习实践指南", "深度学习框架对比")
            );
            ChatLanguageModel llm = mockLlm(response);
            clarifierService = new ClarifierService(mockModelFactory(llm));

            ClarifyResult result = clarifierService.clarify("帮我学习AI");

            assertFalse(result.isClear());
            assertNotNull(result.getMessage());
            assertFalse(result.getDirections().isEmpty());
            assertTrue(result.getDirections().size() >= 3);
        }

        @Test
        @DisplayName("清晰查询：'搜索笔记里关于 BM25 的内容' → 应返回清晰")
        void clearQuery_shouldReturnClear() {
            String response = clarifyClearResponse("搜索笔记中关于 BM25 算法的相关内容");
            ChatLanguageModel llm = mockLlm(response);
            clarifierService = new ClarifierService(mockModelFactory(llm));

            ClarifyResult result = clarifierService.clarify("搜索笔记里关于 BM25 的内容");

            assertTrue(result.isClear());
            assertNotNull(result.getBrief());
        }

        @Test
        @DisplayName("多意图混合：'搜索笔记+总结知识库+安排复习' → 应返回不清晰或拆分")
        void multiIntentQuery_shouldHandleGracefully() {
            String response = clarifyUnclearResponse(
                    "你的需求包含多个操作，建议先选择一个方向：",
                    Arrays.asList("搜索并整理笔记", "总结知识库内容", "查看今日复习计划")
            );
            ChatLanguageModel llm = mockLlm(response);
            clarifierService = new ClarifierService(mockModelFactory(llm));

            ClarifyResult result = clarifierService.clarify("帮我搜索笔记，然后总结知识库里的内容，再安排今天的复习");

            assertFalse(result.isClear());
            assertTrue(result.getDirections().size() >= 2);
        }

        @Test
        @DisplayName("歧义问句：'帮我看看' → 应返回不清晰")
        void ambiguousQuery_shouldReturnUnclear() {
            String response = clarifyUnclearResponse(
                    "不太确定你想做什么，以下是几个常用操作：",
                    Arrays.asList("查看笔记列表", "查看今日复习", "查看知识库文档")
            );
            ChatLanguageModel llm = mockLlm(response);
            clarifierService = new ClarifierService(mockModelFactory(llm));

            ClarifyResult result = clarifierService.clarify("帮我看看");

            assertFalse(result.isClear());
        }

        @Test
        @DisplayName("专业术语查询：'Transformer自注意力机制原理' → 应返回清晰")
        void technicalTermQuery_shouldReturnClear() {
            String response = clarifyClearResponse("解释 Transformer 架构中自注意力机制的原理和计算过程");
            ChatLanguageModel llm = mockLlm(response);
            clarifierService = new ClarifierService(mockModelFactory(llm));

            ClarifyResult result = clarifierService.clarify("Transformer自注意力机制原理");

            assertTrue(result.isClear());
        }

        @Test
        @DisplayName("LLM 返回非法 JSON 时应降级为清晰")
        void invalidJsonResponse_shouldFallbackToClear() {
            ChatLanguageModel llm = mockLlm("这不是一个有效的JSON响应");
            clarifierService = new ClarifierService(mockModelFactory(llm));

            ClarifyResult result = clarifierService.clarify("任何查询");

            assertTrue(result.isClear(), "解析失败时应降级为清晰，直接放行");
            assertEquals("任何查询", result.getBrief());
        }
    }

    @Nested
    @DisplayName("Supervisor 任务拆分判断")
    class SupervisorTests {

        @Test
        @DisplayName("简单查询应返回空子任务列表")
        void simpleQuery_shouldReturnEmptySubTasks() {
            ChatLanguageModel llm = mockLlm("[]");
            supervisorService = new SupervisorService(mockModelFactory(llm));

            List<SubTask> tasks = supervisorService.plan("搜索笔记 BM25");

            assertTrue(tasks.isEmpty(), "简单查询不应拆分");
        }

        @Test
        @DisplayName("复杂查询应拆分为多个子任务")
        void complexQuery_shouldReturnSubTasks() {
            String response = supervisorPlanResponse(Arrays.asList(
                    new String[]{"搜索笔记", "搜索所有与AI相关的笔记", "searchNotes"},
                    new String[]{"检索知识库", "从知识库检索AI相关文档", "ragSummary"},
                    new String[]{"查看统计", "获取笔记总数和分类", "getNoteStats"}
            ));
            ChatLanguageModel llm = mockLlm(response);
            supervisorService = new SupervisorService(mockModelFactory(llm));

            List<SubTask> tasks = supervisorService.plan("帮我整理AI学习笔记并总结知识库里的内容");

            assertEquals(3, tasks.size());
            assertEquals("R-1", tasks.get(0).getId());
            assertEquals("搜索笔记", tasks.get(0).getLabel());
            assertEquals("searchNotes", tasks.get(0).getToolHint());
        }

        @Test
        @DisplayName("LLM 返回非法 JSON 时应降级为空列表")
        void invalidJson_shouldFallbackToEmpty() {
            ChatLanguageModel llm = mockLlm("这不是JSON");
            supervisorService = new SupervisorService(mockModelFactory(llm));

            List<SubTask> tasks = supervisorService.plan("任何查询");

            assertTrue(tasks.isEmpty(), "解析失败时应走单 Agent");
        }
    }
}
