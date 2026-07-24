package com.rag.notebook.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeDocument(
        String id,
        String filename,
        String originalFilename,
        String userId,
        String md5,
        int chunkCount,
        String preview,
        String status,
        String createdAt
) {}
