package com.rag.notebook.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ReorderRequest {

    @NotBlank(message = "查询内容不能为空")
    private String query;

    private List<String> documents;
}
