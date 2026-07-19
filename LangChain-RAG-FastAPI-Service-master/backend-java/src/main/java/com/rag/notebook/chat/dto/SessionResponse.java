package com.rag.notebook.chat.dto;

import java.util.List;

public record SessionResponse(
        String sessionId,
        List<String[]> history
) {}
