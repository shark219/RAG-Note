package com.rag.notebook.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TextAddRequest {

    @NotBlank(message = "文本内容不能为空")
    private String content;

    private String filename;
}
