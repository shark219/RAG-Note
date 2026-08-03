package com.rag.notebook.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class TextChunker {

    private static final String[] SEPARATORS = {
            "\n\n", "\n", "\u3002", "\uff01", "\uff1f", ".", "!", "?",
            "\uff1b", ";", "\uff0c", ","
    };

    private TextChunker() {
    }

    static List<String> split(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        int safeChunkSize = Math.max(1, chunkSize);
        int safeOverlap = Math.max(0, Math.min(chunkOverlap, safeChunkSize - 1));

        List<String> pieces = new ArrayList<>();
        splitSegment(text, safeChunkSize, pieces);

        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (piece == null || piece.isBlank()) {
                continue;
            }

            if (current.length() + piece.length() > safeChunkSize && current.length() > 0) {
                addChunk(chunks, current);
                String overlap = overlap(current, safeOverlap);
                current.setLength(0);
                if (!overlap.isBlank() && overlap.length() + piece.length() <= safeChunkSize) {
                    current.append(overlap);
                }
            }

            if (current.length() + piece.length() > safeChunkSize && current.length() > 0) {
                addChunk(chunks, current);
                current.setLength(0);
            }

            current.append(piece);
        }

        if (current.length() > 0) {
            addChunk(chunks, current);
        }
        return chunks;
    }

    private static void splitSegment(String segment, int chunkSize, List<String> pieces) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        if (segment.length() <= chunkSize) {
            pieces.add(segment);
            return;
        }

        for (String separator : SEPARATORS) {
            if (!segment.contains(separator)) {
                continue;
            }
            String[] parts = segment.split("(?<=" + Pattern.quote(separator) + ")");
            if (parts.length <= 1) {
                continue;
            }
            for (String part : parts) {
                if (!part.isEmpty()) {
                    splitSegment(part, chunkSize, pieces);
                }
            }
            return;
        }

        for (int start = 0; start < segment.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, segment.length());
            pieces.add(segment.substring(start, end));
        }
    }

    private static void addChunk(List<String> chunks, StringBuilder chunk) {
        String value = chunk.toString().trim();
        if (!value.isEmpty()) {
            chunks.add(value);
        }
    }

    private static String overlap(StringBuilder chunk, int chunkOverlap) {
        if (chunkOverlap <= 0) {
            return "";
        }
        // 只掐掉开头的空白，保留原有的换行结构；如果对整体 trim() 会把结尾的 \n 削掉，
        // 导致重叠内容和下一段拼接时两行粘连在一起（比如表格行/命令行被硬拼成一行）。
        String value = chunk.toString();
        int start = Math.max(0, value.length() - chunkOverlap);
        return value.substring(start).stripLeading();
    }
}
