package com.rag.notebook.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class QueryRequest {

    @NotBlank(message = "查询内容不能为空")
    private String query;

    private String sessionId;

    /** 是否为重新生成（跳过保存用户消息） */
    private boolean regenerate;

    /** 是否启用知识库检索（默认 true） */
    private boolean enableKnowledge = true;

    /** 是否启用笔记检索（默认 true） */
    private boolean enableNotes = true;

    /** 附件 ID 列表 */
    private List<String> fileIds;

    /** 选中的知识库文档 filename 列表（空=搜索全部） */
    private List<String> selectedKnowledgeDocs;

    /** 选中的笔记 ID 列表（空=搜索全部） */
    private List<String> selectedNotes;
}
