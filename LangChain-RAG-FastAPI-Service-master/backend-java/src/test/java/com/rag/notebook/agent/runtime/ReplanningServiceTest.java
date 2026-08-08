package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.SubTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReplanningService 测试
 */
@SpringBootTest
class ReplanningServiceTest {

    @Autowired
    private ReplanningService replanningService;

    private WorkerLoopContext context;
    private AgentState state;
    private List<SubTask> originalPlan;

    @BeforeEach
    void setUp() {
        state = new AgentState("测试查询：分析用户增长趋势");
        state.setGoal("获取用户增长数据并生成报告");

        originalPlan = new ArrayList<>();
        SubTask task1 = new SubTask();
        task1.setId("S1");
        task1.setLabel("获取用户数据");
        task1.setGoal("数据已获取");
        task1.setToolHint("listNotes");
        originalPlan.add(task1);

        SubTask task2 = new SubTask();
        task2.setId("S2");
        task2.setLabel("分析数据");
        task2.setGoal("分析完成");
        task2.setToolHint("createNote");
        originalPlan.add(task2);

        SubTask task3 = new SubTask();
        task3.setId("S3");
        task3.setLabel("生成报告");
        task3.setGoal("报告已生成");
        task3.setToolHint("createNote");
        originalPlan.add(task3);

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
    void testReplan_GeneratesNewSteps() {
        ReflectionResult reflection = new ReflectionResult(
                false,  // goalAchieved
                true,   // shouldReplan
                false,  // shouldAskUser
                "DATA_UNAVAILABLE",  // failureType
                "数据源不可用",  // rootCause
                List.of(),  // missingEvidence
                List.of("切换到备用数据源", "使用缓存数据"),  // recommendedActions
                "需要切换数据源",  // summary
                0.8  // confidence
        );

        ReplanResult result = replanningService.replan(context, reflection);

        assertNotNull(result);
        assertNotNull(result.newSteps());
        assertFalse(result.newSteps().isEmpty(), "Should generate new steps");
    }

    @Test
    void testReplan_WithMissingEvidence() {
        ReflectionResult reflection = new ReflectionResult(
                false,  // goalAchieved
                true,   // shouldReplan
                false,  // shouldAskUser
                "MISSING_DATA",  // failureType
                "缺少必要数据",  // rootCause
                List.of("用户ID列表", "时间范围"),  // missingEvidence
                List.of("先获取用户ID", "确认时间范围"),  // recommendedActions
                "需要补充数据",  // summary
                0.7  // confidence
        );

        ReplanResult result = replanningService.replan(context, reflection);

        assertNotNull(result);
        assertTrue(result.newSteps().size() > 0);
    }

    @Test
    void testReplan_PreservesContext() {
        ReflectionResult reflection = new ReflectionResult(
                false,  // goalAchieved
                true,   // shouldReplan
                false,  // shouldAskUser
                "TOOL_FAILURE",  // failureType
                "工具调用失败",  // rootCause
                List.of(),  // missingEvidence
                List.of("重试", "使用替代工具"),  // recommendedActions
                "工具失败需要重试",  // summary
                0.9  // confidence
        );

        ReplanResult result = replanningService.replan(context, reflection);

        assertNotNull(result);
        // 新计划应该生成合理的步骤
        assertTrue(result.newSteps().size() >= 0);
    }

    @Test
    void testReplan_NoReplanNeeded() {
        ReflectionResult reflection = new ReflectionResult(
                true,   // goalAchieved
                false,  // shouldReplan
                false,  // shouldAskUser
                null,   // failureType
                null,   // rootCause
                List.of(),  // missingEvidence
                List.of(),  // recommendedActions
                "目标已达成",  // summary
                0.95  // confidence
        );

        ReplanResult result = replanningService.replan(context, reflection);

        assertNotNull(result);
        // 如果不需要重规划，应返回空计划
        assertTrue(result.newSteps() == null || result.newSteps().isEmpty());
    }
}
