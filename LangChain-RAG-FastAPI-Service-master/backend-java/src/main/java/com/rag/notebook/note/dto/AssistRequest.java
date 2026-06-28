package com.rag.notebook.note.dto;

import lombok.Data;

@Data
public class AssistRequest {

    private String content = "";
    private String action = "continue"; // continue, expand, summarize
}
