package com.rag.notebook.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeDocument(
        String id,
        String filename,
        @JsonProperty("original_filename") String originalFilename,
        @JsonProperty("user_id") String userId,
        @JsonProperty("chunk_count") int chunkCount,
        String preview,
        @JsonProperty("created_at") String createdAt
) {}
