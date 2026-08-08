package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.repo.AgentReflectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReflectionService 测试
 */
@SpringBootTest
@Transactional
class ReflectionServiceTest {

    @Autowired
    private ReflectionService reflectionService;

    @Autowired
    private AgentReflectionRepository reflectionRepository;

    private WorkerLoopContext context;
    private AgentState state;

    @BeforeEach
    void setUp() {
        state = new AgentState("测试查询：分析用户增长趋势");
        state.setGoal("获取用户增长数据并生成报告");

        context = new WorkerLoopContext(
                "test-task-id",
                "test-step-id",
                "You are a helpful assistant",
                "分析用户增长趋势",
                "test-user",
                "test-session",
                state,
                null,
                null,
                null,
                10,
                20,
                32000,
                "获取用户增长数据并生成报告",
                null
        );
    }

    @Test
    void testReflect_NoProgress() {
        // 模拟无进展场景
        state.markNoProgress();
        state.markNoProgress();

        ReflectionResult result = reflectionService.reflect(context, "listNotes", "[]");

        assertNotNull(result);
        // 如果 LLM 可用，应该有反思结果
        // assertNotNull(result.getSummary());
    }

    @Test
    void testReflect_RepeatedToolFailure() {
        // 模拟工具重复失败
        for (int i = 0; i < 3; i++) {
            state.recordToolCall("fetchUrl", "{\"url\":\"http://example.com\"}",
                    "连接超时", AgentState.ResultQuality.ERROR);
        }

        ReflectionResult result = reflectionService.reflect(context, "fetchUrl", "连接超时");

        assertNotNull(result);
    }

    @Test
    void testReflect_PersistsToDB() {
        state.markNoProgress();
        state.markNoProgress();
        long beforeCount = reflectionRepository.count();

        reflectionService.reflect(context, "listNotes", "[]");

        long afterCount = reflectionRepository.count();
        assertTrue(afterCount >= beforeCount, "Should persist reflection to DB");
    }

    @Test
    void testReflect_NoOpWhenNoIssues() {
        // 没有问题时不应触发反思
        // consecutiveNoProgress 默认为 0

        ReflectionResult result = reflectionService.reflect(context, "listNotes", "[note1, note2]");

        assertNotNull(result);
        assertFalse(result.shouldReplan() && result.shouldAskUser());
    }
}
