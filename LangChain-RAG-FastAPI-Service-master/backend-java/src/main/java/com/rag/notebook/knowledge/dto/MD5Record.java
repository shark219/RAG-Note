package com.rag.notebook.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MD5Record(
        String md5,
        String filename,
        String originalFilename,
        String uploadTime
) {}
