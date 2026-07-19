package com.rag.notebook.note.dto;

import java.util.List;

public record NoteListResponse(
        List<NoteResponse> notes,
        long totalCount
) {}
