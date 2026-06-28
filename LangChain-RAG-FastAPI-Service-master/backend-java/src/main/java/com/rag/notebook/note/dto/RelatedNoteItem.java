package com.rag.notebook.note.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelatedNoteItem(
        String id,
        String title,
        String contentPreview,
        String content,
        float similarity,
        String source
) {}
