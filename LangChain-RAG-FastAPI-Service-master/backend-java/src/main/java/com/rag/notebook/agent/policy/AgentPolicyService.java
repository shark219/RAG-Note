package com.rag.notebook.agent.policy;

import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.tool.RiskLevel;
import com.rag.notebook.agent.tool.ToolDefinition;
import com.rag.notebook.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent 策略服务
 * 统一管理预算控制、审批策略、频率限制、白名单验证
 */
@Slf4j
@Service
public class AgentPolicyService {

    private final ToolRegistry toolRegistry;
    private final Set<String> allowedDomains;
    private final Map<String, Integer> toolRateLimits;

    public AgentPolicyService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.allowedDomains = initAllowedDomains();
        this.toolRateLimits = initToolRateLimits();
    }

    /**
     * 检查预算是否超限
     */
    public boolean checkBudget(BudgetConfig budget, AgentState state, int currentIteration) {
        // 检查最大迭代次数
        if (currentIteration > budget.getMaxIterations()) {
            log.warn("超过最大迭代次数: current={}, max={}", currentIteration, budget.getMaxIterations());
            return false;
        }

        // 检查最大工具调用次数
        if (state.getToolHistory().size() >= budget.getMaxToolCalls()) {
            log.warn("超过最大工具调用次数: current={}, max={}",
                    state.getToolHistory().size(), budget.getMaxToolCalls());
            return false;
        }

        // 检查最大连续失败次数
        if (state.getConsecutiveNoProgress() >= budget.getMaxConsecutiveFailures()) {
            log.warn("超过最大连续失败次数: current={}, max={}",
                    state.getConsecutiveNoProgress(), budget.getMaxConsecutiveFailures());
            return false;
        }

        // 检查同一工具最大调用次数
        Map<String, Long> toolCallCounts = new HashMap<>();
        for (AgentState.ToolCallRecord record : state.getToolHistory()) {
            toolCallCounts.merge(record.toolName(), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> entry : toolCallCounts.entrySet()) {
            if (entry.getValue() >= budget.getMaxSameToolCalls()) {
                log.warn("工具 {} 调用次数超限: current={}, max={}",
                        entry.getKey(), entry.getValue(), budget.getMaxSameToolCalls());
                return false;
            }
        }

        // TODO: 检查最大 Token 消耗
        // TODO: 检查最大运行时长

        return true;
    }

    /**
     * 获取预算违规原因
     */
    public String getBudgetViolationReason(BudgetConfig budget, AgentState state, int currentIteration) {
        if (currentIteration > budget.getMaxIterations()) {
            return "达到最大迭代次数限制: " + budget.getMaxIterations();
        }
        if (state.getToolHistory().size() >= budget.getMaxToolCalls()) {
            return "达到最大工具调用次数限制: " + budget.getMaxToolCalls();
        }
        if (state.getConsecutiveNoProgress() >= budget.getMaxConsecutiveFailures()) {
            return "达到最大连续失败次数限制: " + budget.getMaxConsecutiveFailures();
        }

        Map<String, Long> toolCallCounts = new HashMap<>();
        for (AgentState.ToolCallRecord record : state.getToolHistory()) {
            toolCallCounts.merge(record.toolName(), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> entry : toolCallCounts.entrySet()) {
            if (entry.getValue() >= budget.getMaxSameToolCalls()) {
                return "工具 " + entry.getKey() + " 调用次数超限: " + budget.getMaxSameToolCalls();
            }
        }

        return "未知预算违规";
    }

    /**
     * 检查工具是否需要审批
     */
    public boolean requiresApproval(String toolName, String arguments, AgentState state) {
        Optional<ToolDefinition> toolDef = toolRegistry.get(toolName);
        if (toolDef.isEmpty()) {
            return false;
        }

        // 工具定义中明确要求审批
        if (toolDef.get().isRequiresApproval()) {
            log.info("工具 {} 需要审批（工具定义要求）", toolName);
            return true;
        }

        // 高风险工具需要审批
        if (toolDef.get().getRiskLevel() == RiskLevel.CRITICAL) {
            log.info("工具 {} 需要审批（CRITICAL 风险级别）", toolName);
            return true;
        }

        return false;
    }

    /**
     * 检查工具调用频率限制
     */
    public boolean checkRateLimit(String toolName, String userId, AgentState state) {
        Integer limit = toolRateLimits.get(toolName);
        if (limit == null) {
            return true; // 无限制
        }

        long count = state.getToolHistory().stream()
                .filter(r -> r.toolName().equals(toolName))
                .count();

        if (count >= limit) {
            log.warn("工具 {} 超过频率限制: current={}, limit={}", toolName, count, limit);
            return false;
        }

        return true;
    }

    /**
     * 检查 URL 是否在白名单中
     */
    public boolean isUrlAllowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            String domain = extractDomain(url);
            for (String allowedPattern : allowedDomains) {
                if (matchDomain(domain, allowedPattern)) {
                    return true;
                }
            }
            log.warn("URL 不在白名单中: {}", url);
            return false;
        } catch (Exception e) {
            log.error("解析 URL 失败: {}", url, e);
            return false;
        }
    }

    /**
     * 提取域名
     */
    private String extractDomain(String url) {
        String normalized = url.toLowerCase();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }

        try {
            java.net.URL urlObj = new java.net.URL(normalized);
            return urlObj.getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 匹配域名（支持通配符）
     */
    private boolean matchDomain(String domain, String pattern) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1); // 移除 *
            return domain.endsWith(suffix) || domain.equals(suffix.substring(1));
        }
        return domain.equals(pattern);
    }

    /**
     * 初始化允许的域名
     */
    private Set<String> initAllowedDomains() {
        Set<String> domains = new HashSet<>();
        domains.add("*.baidu.com");
        domains.add("*.zhihu.com");
        domains.add("*.csdn.net");
        domains.add("*.github.com");
        domains.add("*.github.io");
        domains.add("*.stackoverflow.com");
        domains.add("*.wikipedia.org");
        domains.add("*.medium.com");
        domains.add("*.juejin.cn");
        domains.add("*.segmentfault.com");
        domains.add("*.oschina.net");
        domains.add("*.infoq.cn");
        return domains;
    }

    /**
     * 初始化工具频率限制
     */
    private Map<String, Integer> initToolRateLimits() {
        Map<String, Integer> limits = new HashMap<>();
        limits.put("fetchUrl", 5);      // 最多 5 次
        limits.put("deleteNote", 3);    // 最多 3 次
        limits.put("mergeNotes", 2);    // 最多 2 次
        return limits;
    }

    /**
     * 添加允许的域名
     */
    public void addAllowedDomain(String domain) {
        allowedDomains.add(domain);
        log.info("添加允许的域名: {}", domain);
    }

    /**
     * 设置工具频率限制
     */
    public void setToolRateLimit(String toolName, int limit) {
        toolRateLimits.put(toolName, limit);
        log.info("设置工具频率限制: tool={}, limit={}", toolName, limit);
    }
}
