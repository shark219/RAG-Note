package com.rag.notebook.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 */
@Slf4j
@Service
public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    public ToolRegistry() {
        registerDefaultTools();
    }

    /**
     * 注册工具
     */
    public void register(ToolDefinition tool) {
        tools.put(tool.getName(), tool);
        log.info("注册工具: name={}, category={}, riskLevel={}",
                tool.getName(), tool.getCategory(), tool.getRiskLevel());
    }

    /**
     * 获取工具定义
     */
    public Optional<ToolDefinition> get(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    /**
     * 获取所有工具
     */
    public List<ToolDefinition> getAll() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 按分类查询
     */
    public List<ToolDefinition> getByCategory(ToolCategory category) {
        return tools.values().stream()
                .filter(t -> t.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * 按风险级别查询
     */
    public List<ToolDefinition> getByRiskLevel(RiskLevel riskLevel) {
        return tools.values().stream()
                .filter(t -> t.getRiskLevel() == riskLevel)
                .collect(Collectors.toList());
    }

    /**
     * 检查工具是否存在
     */
    public boolean exists(String toolName) {
        return tools.containsKey(toolName);
    }

    /**
     * 注册默认工具
     */
    private void registerDefaultTools() {
        // READ 类工具
        register(ToolDefinition.builder()
                .name("listNotes")
                .description("列出笔记列表")
                .category(ToolCategory.READ)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(10)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("getNote")
                .description("获取笔记详情")
                .category(ToolCategory.READ)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(10)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("getNoteStats")
                .description("获取笔记统计")
                .category(ToolCategory.READ)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(10)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("getRecentNotes")
                .description("获取最近笔记")
                .category(ToolCategory.READ)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(10)
                .requiresApproval(false)
                .build());

        // SEARCH 类工具
        register(ToolDefinition.builder()
                .name("searchNotes")
                .description("搜索笔记")
                .category(ToolCategory.SEARCH)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(15)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("ragSummary")
                .description("RAG 检索总结")
                .category(ToolCategory.SEARCH)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(20)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("getRelatedNotes")
                .description("获取相关笔记")
                .category(ToolCategory.SEARCH)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(15)
                .requiresApproval(false)
                .build());

        // WRITE 类工具
        register(ToolDefinition.builder()
                .name("createNote")
                .description("创建笔记")
                .category(ToolCategory.WRITE)
                .riskLevel(RiskLevel.MEDIUM)
                .timeoutSeconds(15)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("editNote")
                .description("编辑笔记")
                .category(ToolCategory.WRITE)
                .riskLevel(RiskLevel.MEDIUM)
                .timeoutSeconds(15)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("appendNote")
                .description("追加笔记内容")
                .category(ToolCategory.WRITE)
                .riskLevel(RiskLevel.MEDIUM)
                .timeoutSeconds(15)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("deleteNote")
                .description("删除笔记")
                .category(ToolCategory.WRITE)
                .riskLevel(RiskLevel.HIGH)
                .timeoutSeconds(10)
                .requiresApproval(true)
                .build());

        register(ToolDefinition.builder()
                .name("mergeNotes")
                .description("合并笔记")
                .category(ToolCategory.WRITE)
                .riskLevel(RiskLevel.HIGH)
                .timeoutSeconds(20)
                .requiresApproval(true)
                .build());

        // EXTERNAL 类工具
        register(ToolDefinition.builder()
                .name("fetchUrl")
                .description("抓取网页内容")
                .category(ToolCategory.EXTERNAL)
                .riskLevel(RiskLevel.HIGH)
                .timeoutSeconds(30)
                .requiresApproval(false)
                .allowedDomains(Arrays.asList(
                        "*.baidu.com", "*.zhihu.com", "*.csdn.net",
                        "*.github.com", "*.github.io", "*.stackoverflow.com",
                        "*.wikipedia.org", "*.medium.com"
                ))
                .build());

        // ANALYSIS 类工具
        register(ToolDefinition.builder()
                .name("generateDiagram")
                .description("生成图表")
                .category(ToolCategory.ANALYSIS)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(20)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("generateMindMap")
                .description("生成思维导图")
                .category(ToolCategory.ANALYSIS)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(20)
                .requiresApproval(false)
                .build());

        // REVIEW 类工具
        register(ToolDefinition.builder()
                .name("getTodayReviews")
                .description("获取今日复习")
                .category(ToolCategory.REVIEW)
                .riskLevel(RiskLevel.LOW)
                .timeoutSeconds(10)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("markReviewed")
                .description("标记已复习")
                .category(ToolCategory.REVIEW)
                .riskLevel(RiskLevel.MEDIUM)
                .timeoutSeconds(10)
                .requiresApproval(false)
                .build());

        register(ToolDefinition.builder()
                .name("scheduleReview")
                .description("安排复习")
                .category(ToolCategory.REVIEW)
                .riskLevel(RiskLevel.MEDIUM)
                .timeoutSeconds(10)
                .requiresApproval(false)
                .build());

        log.info("已注册 {} 个默认工具", tools.size());
    }
}
