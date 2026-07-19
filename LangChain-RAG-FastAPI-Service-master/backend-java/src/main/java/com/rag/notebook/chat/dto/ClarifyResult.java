package com.rag.notebook.chat.dto;

import lombok.Data;
import java.util.List;

@Data
public class ClarifyResult {

    private boolean isClear;
    private String brief;
    private String message;
    private List<String> directions;
}
