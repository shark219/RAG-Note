package com.rag.notebook.knowledge.dto;

import java.util.List;

public record ChunkDetail(
        String chunkId,
        int index,
        String content,
        Integer page,
        List<String> images
) {
    public ChunkDetail {
        if (images == null) images = List.of();
    }
}
