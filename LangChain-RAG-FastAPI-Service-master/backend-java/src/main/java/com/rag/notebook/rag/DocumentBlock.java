package com.rag.notebook.rag;

import java.util.List;

record DocumentBlock(
        Type type,
        String text,
        String markdown,
        int headingLevel,
        List<String> headingPath,
        Integer pageStart,
        Integer pageEnd,
        String contentType
) {

    enum Type {
        HEADING,
        PARAGRAPH,
        LIST,
        CODE,
        TABLE,
        IMAGE,
        PAGE_BREAK
    }

    DocumentBlock {
        text = text == null ? "" : text;
        markdown = markdown == null ? text : markdown;
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        contentType = contentType == null || contentType.isBlank() ? type.name().toLowerCase() : contentType;
    }

    boolean hasText() {
        return !markdown.isBlank();
    }
}
