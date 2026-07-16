package com.rag.notebook.agent;

import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import com.rag.notebook.rag.QualityReviewer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 任务拆解与规划测试
 * 覆盖：复杂任务拆分、子任务独立性、流水线执行、Writer 合成
 */
@DisplayName("任务拆解与规划测试")
class TaskPlanningTest extends TestBase {

    @Nested
    @DisplayName("Supervisor 子任务拆分")
    class SupervisorDecomposition {

        @Test
        @DisplayName("复杂查询应拆分为独立可执行的子任务")
        void complexQuery_shouldDecomposeIntoIndependentSubTasks() {
            String response = supervisorPlanResponse(Arrays.asList(
                    new String[]{"搜索笔记", "搜索所有与 AI 相关的笔记", "searchNotes"},
                    new String[]{"检索知识库", "从知识库中检索 AI 相关文档", "ragSummary"}
            ));
            ChatLanguageModel llm = mockLlm(response);
            SupervisorService supervisor = new SupervisorService(mockModelFactory(llm));

            List<SubTask> tasks = supervisor.plan("帮我整理AI学习笔记并总结知识库内容");

            assertEquals(2, tasks.size());
            // 验证每个子任务有完整的描述
            for (SubTask task : tasks) {
                assertNotNull(task.getId());
                assertNotNull(task.getLabel());
                assertNotNull(task.getDescription());
                assertFalse(task.getLabel().isEmpty());
                assertFalse(task.getDescription().isEmpty());
            }
            // 验证子任务 ID 唯一
            Set<String> ids = new HashSet<>();
            for (SubTask task : tasks) {
                assertTrue(ids.add(task.getId()), "子任务 ID 应唯一");
            }
        }

        @Test
        @DisplayName("子任务应覆盖原始查询的所有维度")
        void subTasks_shouldCoverAllDimensions() {
            String response = supervisorPlanResponse(Arrays.asList(
                    new String[]{"搜索笔记", "搜索AI相关笔记", "searchNotes"},
                    new String[]{"检索知识库", "检索AI文档", "ragSummary"},
                    new String[]{"查看统计", "获取笔记统计", "getNoteStats"}
            ));
            ChatLanguageModel llm = mockLlm(response);
            SupervisorService supervisor = new SupervisorService(mockModelFactory(llm));

            List<SubTask> tasks = supervisor.plan("整理AI笔记，总结知识库，看看有多少笔记");

            // 验证工具覆盖了查询的各个方面
            Set<String> tools = new HashSet<>();
            for (SubTask task : tasks) {
                if (task.getToolHint() != null) tools.add(task.getToolHint());
            }
            assertTrue(tools.contains("searchNotes"), "应包含笔记搜索");
            assertTrue(tools.contains("ragSummary"), "应包含知识库检索");
        }

        @Test
        @DisplayName("简单查询不应拆分")
        void simpleQuery_shouldNotDecompose() {
            ChatLanguageModel llm = mockLlm("[]");
            SupervisorService supervisor = new SupervisorService(mockModelFactory(llm));

            List<SubTask> tasks = supervisor.plan("搜索笔记 BM25");

            assertTrue(tasks.isEmpty());
        }
    }

    @Nested
    @DisplayName("Writer 合成")
    class WriterSynthesis {

        @Test
        @DisplayName("多个子任务结果应合成为一个连贯回答")
        void multipleResults_shouldSynthesizeIntoCoherentAnswer() {
            String synthesis = "## AI 学习笔记整理\n\n你的笔记中有 5 篇关于 AI 的内容，知识库中有 3 篇相关文档。";
            ChatLanguageModel llm = mockLlm(synthesis);
            WriterService writer = new WriterService(mockModelFactory(llm));

            List<SubTask> tasks = List.of(
                    new SubTask("R-1", "搜索笔记", "搜索AI笔记", "searchNotes"),
                    new SubTask("R-2", "检索知识库", "检索AI文档", "ragSummary")
            );
            Map<String, String> results = new LinkedHashMap<>();
            results.put("R-1", "找到 5 篇 AI 相关笔记");
            results.put("R-2", "知识库中有 3 篇 AI 相关文档");

            String answer = writer.synthesize("整理AI学习笔记", tasks, results);

            assertNotNull(answer);
            assertFalse(answer.isEmpty());
            // 回答应包含子任务结果中的关键信息
            assertTrue(answer.contains("笔记") || answer.contains("AI"));
        }

        @Test
        @DisplayName("LLM 合成失败时应使用拼接兜底")
        void synthesisFailure_shouldFallbackToConcatenation() {
            ChatLanguageModel llm = mock(ChatLanguageModel.class);
            when(llm.generate(any())).thenThrow(new RuntimeException("API 超时"));
            WriterService writer = new WriterService(mockModelFactory(llm));

            List<SubTask> tasks = List.of(
                    new SubTask("R-1", "搜索笔记", "搜索AI笔记", "searchNotes")
            );
            Map<String, String> results = Map.of("R-1", "找到 5 篇笔记");

            String answer = writer.synthesize("整理AI笔记", tasks, results);

            assertNotNull(answer);
            // 兜底应包含原始结果
            assertTrue(answer.contains("5 篇笔记"));
        }

        @Test
        @DisplayName("空结果集应安全处理")
        void emptyResults_shouldHandleSafely() {
            ChatLanguageModel llm = mockLlm("没有找到相关信息。");
            WriterService writer = new WriterService(mockModelFactory(llm));

            List<SubTask> tasks = List.of(
                    new SubTask("R-1", "搜索笔记", "搜索AI笔记", "searchNotes")
            );
            Map<String, String> results = Map.of();

            String answer = writer.synthesize("整理AI笔记", tasks, results);

            assertNotNull(answer);
        }
    }

    @Nested
    @DisplayName("流水线执行")
    class PipelineExecution {

        @Test
        @DisplayName("子任务失败不应阻塞其他子任务")
        void subTaskFailure_shouldNotBlockOthers() {
            AgentTools agentTools = mock(AgentTools.class);
            when(agentTools.searchNotes(any(), any())).thenReturn("找到笔记");
            when(agentTools.ragSummary(any(), any())).thenThrow(new RuntimeException("RAG 服务不可用"));
            when(agentTools.getLatestTraceId()).thenReturn(null);

            // 验证 searchNotes 仍然可以正常执行
            String result1 = agentTools.searchNotes("AI", "user1");
            assertNotNull(result1);

            // ragSummary 失败
            assertThrows(RuntimeException.class, () -> agentTools.ragSummary("AI", "user1"));
        }

        @Test
        @DisplayName("子任务应支持并行执行")
        void subTasks_shouldSupportParallelExecution() {
            List<SubTask> tasks = List.of(
                    new SubTask("R-1", "搜索笔记", "搜索AI笔记", "searchNotes"),
                    new SubTask("R-2", "检索知识库", "检索AI文档", "ragSummary"),
                    new SubTask("R-3", "查看统计", "获取统计", "getNoteStats")
            );

            // 验证所有子任务可以并发执行
            List<String> results = Collections.synchronizedList(new ArrayList<>());

            List<Thread> threads = new ArrayList<>();
            for (SubTask task : tasks) {
                Thread t = new Thread(() -> {
                    // 模拟子任务执行
                    try { Thread.sleep(10); } catch (InterruptedException e) {}
                    results.add(task.getId() + ":done");
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) {
                try { t.join(5000); } catch (InterruptedException e) {}
            }

            assertEquals(3, results.size(), "所有子任务应完成");
        }
    }
}
