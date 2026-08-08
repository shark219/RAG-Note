package com.rag.notebook.agent.policy;

import com.rag.notebook.agent.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentPolicyService 测试
 */
@SpringBootTest
class AgentPolicyServiceTest {

    @Autowired
    private AgentPolicyService policyService;

    private BudgetConfig budget;
    private AgentState state;

    @BeforeEach
    void setUp() {
        budget = BudgetConfig.builder()
                .maxIterations(10)
                .maxToolCalls(20)
                .maxTokens(32000)
                .maxRuntimeSeconds(600)
                .maxConsecutiveFailures(5)
                .maxSameToolCalls(3)
                .build();

        state = new AgentState("测试查询");
    }

    @Test
    void testCheckBudget_WithinLimits() {
        boolean result = policyService.checkBudget(budget, state, 5);
        assertTrue(result, "Should pass budget check within limits");
    }

    @Test
    void testCheckBudget_ExceedMaxIterations() {
        boolean result = policyService.checkBudget(budget, state, 11);
        assertFalse(result, "Should fail when exceeding max iterations");

        String reason = policyService.getBudgetViolationReason(budget, state, 11);
        assertTrue(reason.contains("最大迭代次数"));
    }

    @Test
    void testCheckBudget_ExceedMaxToolCalls() {
        // 模拟调用 21 次工具
        for (int i = 0; i < 21; i++) {
            state.recordToolCall("testTool", "{}", "result", AgentState.ResultQuality.GOOD);
        }

        boolean result = policyService.checkBudget(budget, state, 5);
        assertFalse(result, "Should fail when exceeding max tool calls");

        String reason = policyService.getBudgetViolationReason(budget, state, 5);
        assertTrue(reason.contains("最大工具调用次数"));
    }

    @Test
    void testCheckBudget_ExceedConsecutiveFailures() {
        // 模拟 6 次连续无进展
        for (int i = 0; i < 6; i++) {
            state.markNoProgress();
        }

        boolean result = policyService.checkBudget(budget, state, 5);
        assertFalse(result, "Should fail when exceeding consecutive failures");

        String reason = policyService.getBudgetViolationReason(budget, state, 5);
        assertTrue(reason.contains("最大连续失败次数"));
    }

    @Test
    void testCheckBudget_ExceedSameToolCalls() {
        // 同一工具调用 4 次
        for (int i = 0; i < 4; i++) {
            state.recordToolCall("sameTool", "{}", "result", AgentState.ResultQuality.GOOD);
        }

        boolean result = policyService.checkBudget(budget, state, 5);
        assertFalse(result, "Should fail when same tool called too many times");

        String reason = policyService.getBudgetViolationReason(budget, state, 5);
        assertTrue(reason.contains("调用次数超限"));
    }

    @Test
    void testRequiresApproval_DeleteNote() {
        boolean result = policyService.requiresApproval("deleteNote", "{\"noteId\":\"123\"}", state);
        assertTrue(result, "deleteNote should require approval");
    }

    @Test
    void testRequiresApproval_ListNotes() {
        boolean result = policyService.requiresApproval("listNotes", "{}", state);
        assertFalse(result, "listNotes should not require approval");
    }

    @Test
    void testCheckRateLimit_FetchUrl() {
        // 第 1-5 次调用前检查应该通过
        for (int i = 0; i < 5; i++) {
            boolean result = policyService.checkRateLimit("fetchUrl", "user1", state);
            assertTrue(result, "Call " + (i + 1) + " should pass");
            // 记录调用
            state.recordToolCall("fetchUrl", "{\"url\":\"http://test.com\"}", "result",
                    AgentState.ResultQuality.GOOD);
        }

        // 第 6 次调用前检查应该失败（已有 5 次记录）
        boolean result = policyService.checkRateLimit("fetchUrl", "user1", state);
        assertFalse(result, "6th call should exceed rate limit");
    }

    @Test
    void testCheckRateLimit_NoLimit() {
        boolean result = policyService.checkRateLimit("listNotes", "user1", state);
        assertTrue(result, "Tool without rate limit should always pass");
    }

    @Test
    void testIsUrlAllowed_AllowedDomain() {
        assertTrue(policyService.isUrlAllowed("https://github.com/test"));
        assertTrue(policyService.isUrlAllowed("https://www.baidu.com"));
        assertTrue(policyService.isUrlAllowed("https://zhihu.com/question/123"));
    }

    @Test
    void testIsUrlAllowed_DisallowedDomain() {
        assertFalse(policyService.isUrlAllowed("https://malicious-site.com"));
        assertFalse(policyService.isUrlAllowed("http://unknown-domain.xyz"));
    }

    @Test
    void testIsUrlAllowed_Wildcard() {
        assertTrue(policyService.isUrlAllowed("https://user.github.io/project"));
        assertTrue(policyService.isUrlAllowed("https://subdomain.baidu.com"));
    }

    @Test
    void testAddAllowedDomain() {
        assertFalse(policyService.isUrlAllowed("https://example.com"));

        policyService.addAllowedDomain("example.com");
        assertTrue(policyService.isUrlAllowed("https://example.com"));
    }

    @Test
    void testSetToolRateLimit() {
        policyService.setToolRateLimit("createNote", 2);

        // 第 1 次调用前检查
        assertTrue(policyService.checkRateLimit("createNote", "user1", state));
        state.recordToolCall("createNote", "{}", "result", AgentState.ResultQuality.GOOD);

        // 第 2 次调用前检查
        assertTrue(policyService.checkRateLimit("createNote", "user1", state));
        state.recordToolCall("createNote", "{}", "result", AgentState.ResultQuality.GOOD);

        // 第 3 次调用前检查（已有 2 次记录，limit=2，应该失败）
        assertFalse(policyService.checkRateLimit("createNote", "user1", state));
    }
}
