package com.rag.notebook.agent.policy;

import lombok.Data;

/**
 * 策略检查结果
 */
@Data
public class PolicyCheckResult {
    /**
     * 是否允许
     */
    private boolean allowed;

    /**
     * 是否需要审批
     */
    private boolean needsApproval;

    /**
     * 阻塞原因码
     */
    private String blockCode;

    /**
     * 阻塞原因描述
     */
    private String blockReason;

    /**
     * 需要审批的工具名
     */
    private String approvalToolName;

    public static PolicyCheckResult allowed() {
        PolicyCheckResult result = new PolicyCheckResult();
        result.setAllowed(true);
        return result;
    }

    public static PolicyCheckResult blocked(String blockCode, String blockReason) {
        PolicyCheckResult result = new PolicyCheckResult();
        result.setAllowed(false);
        result.setBlockCode(blockCode);
        result.setBlockReason(blockReason);
        return result;
    }

    public static PolicyCheckResult needsApproval(String toolName, String reason) {
        PolicyCheckResult result = new PolicyCheckResult();
        result.setAllowed(false);
        result.setNeedsApproval(true);
        result.setApprovalToolName(toolName);
        result.setBlockReason(reason);
        return result;
    }
}
