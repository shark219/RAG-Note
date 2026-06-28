package com.rag.notebook.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeDocument(
        String id,
        String filename,
        String originalFilename,
        String userId,
        int chunkCount,
        String preview,
        String createdAt
) {}
