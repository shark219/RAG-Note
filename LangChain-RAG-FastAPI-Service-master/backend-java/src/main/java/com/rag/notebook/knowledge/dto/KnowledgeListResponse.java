package com.rag.notebook.knowledge.dto;

import java.util.List;

public record KnowledgeListResponse(
        List<KnowledgeDocument> documents,
        int totalCount
) {}
