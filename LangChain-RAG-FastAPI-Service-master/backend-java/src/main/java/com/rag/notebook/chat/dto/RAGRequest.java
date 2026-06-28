package com.rag.notebook.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RAGRequest {

    @NotBlank(message = "查询内容不能为空")
    private String query;
}
