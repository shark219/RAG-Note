package com.rag.notebook.agent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 工具定义
 */
@Data
@Builder
public class ToolDefinition {
    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 工具分类
     */
    private ToolCategory category;

    /**
     * 风险级别
     */
    private RiskLevel riskLevel;

    /**
     * 超时时间（秒）
     */
    private int timeoutSeconds;

    /**
     * 是否需要审批
     */
    private boolean requiresApproval;

    /**
     * 允许的域名列表（仅 fetchUrl 使用）
     */
    private List<String> allowedDomains;

    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;
}
