package com.rag.notebook.knowledge.dto;

import java.util.List;

public record MD5ListResponse(
        List<MD5Record> records,
        int totalCount
) {}
