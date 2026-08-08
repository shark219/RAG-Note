package com.rag.notebook.agent.tool;

/**
 * 工具分类
 */
public enum ToolCategory {
    /**
     * 读取类：只读操作，无副作用
     */
    READ,

    /**
     * 写入类：创建、修改、删除数据
     */
    WRITE,

    /**
     * 搜索类：检索和查询
     */
    SEARCH,

    /**
     * 外部调用：访问外部资源
     */
    EXTERNAL,

    /**
     * 分析类：生成图表、分析等
     */
    ANALYSIS,

    /**
     * 复习类：复习相关操作
     */
    REVIEW
}
