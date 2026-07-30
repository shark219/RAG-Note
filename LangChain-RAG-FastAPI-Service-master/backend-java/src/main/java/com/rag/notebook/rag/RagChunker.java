package com.rag.notebook.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RagChunker {

    private static final Pattern PDF_PAGE_MARKER = Pattern.compile("^\\[Page\\s+(\\d+)/(\\d+)]$");
    private static final Pattern CHUNK_PAGE_MARKER = Pattern.compile("(?m)^\\[Page\\s+(\\d+)]$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(\\d+(\\.\\d+)*[\\s.．、]+|[一二三四五六七八九十]+[、.．]+|第.{1,12}[章节篇])[\\s\\S]{1,80}$");
    private static final int KNOWLEDGE_CHILD_DEFAULT_CHARS = 500;
    private static final int KNOWLEDGE_CHILD_MIN_CHARS = 300;
    private static final int KNOWLEDGE_PARENT_MIN_CHARS = 1600;
    private static final int NOTE_SHORT_TEXT_MAX_CHARS = 600;

    private RagChunker() {
    }

    static List<RagChunk> splitKnowledge(String filename, String text, int chunkSize, int chunkOverlap) {
        List<SectionBuffer> sections = buildKnowledgeSections(MarkdownBlockParser.parse(text));
        List<RagChunk> chunks = new ArrayList<>();
        int childChunkSize = Math.max(chunkSize, KNOWLEDGE_CHILD_DEFAULT_CHARS);
        int childOverlap = Math.max(chunkOverlap, Math.min(80, childChunkSize / 5));
        int childMinChars = Math.min(KNOWLEDGE_CHILD_MIN_CHARS, childChunkSize / 2);
        int parentMaxChars = Math.max(KNOWLEDGE_PARENT_MIN_CHARS, childChunkSize * 4);
        int parentIndex = 0;

        for (SectionBuffer section : sections) {
            List<KnowledgeChild> childTexts = compactKnowledgeChildren(
                    buildKnowledgeChildren(section, childChunkSize, childOverlap),
                    childMinChars,
                    childChunkSize);
            int parentChars = 0;
            for (KnowledgeChild child : childTexts) {
                if (child == null || child.text().isBlank()) {
                    continue;
                }
                if (parentChars > 0 && parentChars + child.text().length() > parentMaxChars) {
                    parentIndex++;
                    parentChars = 0;
                }

                RagChunk chunk = new RagChunk();
                chunk.setChunkIndex(chunks.size());
                chunk.setContent(buildKnowledgeContent(filename, section.sectionPath,
                        child.pageStart(), child.pageEnd(), child.text()));
                chunk.setRetrievalText(buildRetrievalText(filename, section.sectionPath,
                        child.contentType(), child.text()));
                chunk.setContentType(child.contentType());
                chunk.setSectionPath(section.sectionPath);
                chunk.setPageStart(child.pageStart());
                chunk.setPageEnd(child.pageEnd());
                chunk.setParentIndex(parentIndex);
                chunks.add(chunk);

                parentChars += child.text().length();
            }
            parentIndex++;
        }
        return chunks;
    }

    private static List<KnowledgeChild> compactKnowledgeChildren(List<KnowledgeChild> pieces, int minChars, int targetChars) {
        List<KnowledgeChild> compacted = new ArrayList<>();
        if (pieces == null || pieces.isEmpty()) {
            return compacted;
        }

        int mergeLimit = targetChars + minChars;
        for (KnowledgeChild piece : pieces) {
            if (piece == null || piece.text().isBlank()) {
                continue;
            }
            KnowledgeChild normalized = piece.strip();
            if (!compacted.isEmpty()) {
                int lastIndex = compacted.size() - 1;
                KnowledgeChild previous = compacted.get(lastIndex);
                boolean shouldMerge = normalized.text().length() < minChars || previous.text().length() < minChars;
                if (shouldMerge
                        && canMergeContentTypes(previous.contentType(), normalized.contentType())
                        && previous.text().length() + normalized.text().length() + 2 <= mergeLimit) {
                    compacted.set(lastIndex, previous.merge(normalized));
                    continue;
                }
            }
            compacted.add(normalized);
        }
        return compacted;
    }

    private static List<SectionBuffer> buildKnowledgeSections(List<DocumentBlock> blocks) {
        List<SectionBuffer> sections = new ArrayList<>();
        SectionBuffer current = new SectionBuffer("正文");
        sections.add(current);

        for (DocumentBlock block : blocks) {
            if (block.type() == DocumentBlock.Type.PAGE_BREAK) {
                current.markPage(block.pageStart());
                continue;
            }

            if (block.type() == DocumentBlock.Type.HEADING) {
                String sectionPath = block.headingPath().isEmpty()
                        ? block.text()
                        : String.join(" > ", block.headingPath());
                if (!current.isEmpty()) {
                    current = new SectionBuffer(sectionPath);
                    sections.add(current);
                } else {
                    current.sectionPath = sectionPath;
                }
                current.addBlock(block);
                continue;
            }

            current.addBlock(block);
        }
        return sections.stream().filter(section -> !section.isEmpty()).toList();
    }

    private static List<KnowledgeChild> buildKnowledgeChildren(SectionBuffer section, int targetChars, int overlap) {
        List<KnowledgeChild> children = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentType = "text";
        Integer pageStart = null;
        Integer pageEnd = null;

        for (DocumentBlock block : section.blocks) {
            if (block.type() == DocumentBlock.Type.PAGE_BREAK || !block.hasText()) {
                continue;
            }

            String blockText = block.markdown().strip();
            String blockType = normalizeContentType(block.contentType());
            boolean keepTogether = shouldKeepKnowledgeBlockTogether(blockType, blockText, targetChars);

            if (keepTogether) {
                if (current.length() > 0 && current.length() + blockText.length() + 2 > targetChars) {
                    children.add(new KnowledgeChild(current.toString().strip(), currentType, pageStart, pageEnd));
                    current.setLength(0);
                    currentType = "text";
                    pageStart = null;
                    pageEnd = null;
                }
                if (blockText.length() <= targetChars * 2) {
                    children.add(new KnowledgeChild(blockText, blockType, block.pageStart(), block.pageEnd()));
                } else {
                    children.addAll(splitLargeKnowledgeBlock(blockText, blockType, block.pageStart(), block.pageEnd(),
                            targetChars, overlap));
                }
                continue;
            }

            if (blockText.length() > targetChars * 2) {
                if (current.length() > 0) {
                    children.add(new KnowledgeChild(current.toString().strip(), currentType, pageStart, pageEnd));
                    current.setLength(0);
                    currentType = "text";
                    pageStart = null;
                    pageEnd = null;
                }
                children.addAll(splitLargeKnowledgeBlock(blockText, blockType, block.pageStart(), block.pageEnd(),
                        targetChars, overlap));
                continue;
            }

            if (current.length() > 0 && current.length() + blockText.length() + 2 > targetChars) {
                children.add(new KnowledgeChild(current.toString().strip(), currentType, pageStart, pageEnd));
                String carry = overlapText(current, overlap);
                current.setLength(0);
                if (!carry.isBlank() && carry.length() + blockText.length() + 2 <= targetChars) {
                    current.append(carry).append("\n\n");
                }
                currentType = carry.isBlank() ? "text" : currentType;
                pageStart = carry.isBlank() ? null : pageEnd;
            }

            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(blockText);
            currentType = mergeContentType(currentType, blockType);
            pageStart = minPage(pageStart, block.pageStart());
            pageEnd = maxPage(pageEnd, block.pageEnd());
        }

        if (current.length() > 0) {
            children.add(new KnowledgeChild(current.toString().strip(), currentType, pageStart, pageEnd));
        }
        return children;
    }

    private static List<KnowledgeChild> splitLargeKnowledgeBlock(String text, String contentType,
                                                                 Integer pageStart, Integer pageEnd,
                                                                 int targetChars, int overlap) {
        List<KnowledgeChild> children = new ArrayList<>();
        for (String piece : TextChunker.split(text, targetChars, overlap)) {
            if (!piece.isBlank()) {
                children.add(new KnowledgeChild(piece, contentType, pageStart, pageEnd));
            }
        }
        return children;
    }

    private static boolean shouldKeepKnowledgeBlockTogether(String contentType, String text, int targetChars) {
        if ("code".equals(contentType) || "table".equals(contentType) || "list".equals(contentType)) {
            return text.length() <= Math.max(targetChars * 2, 1200);
        }
        return false;
    }

    private static boolean canMergeContentTypes(String left, String right) {
        return "text".equals(left) || "text".equals(right) || left.equals(right);
    }

    private static String mergeContentType(String current, String next) {
        if (current == null || current.isBlank() || "text".equals(current)) {
            return normalizeContentType(next);
        }
        next = normalizeContentType(next);
        return current.equals(next) ? current : "text";
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "text";
        }
        return switch (contentType) {
            case "code", "table", "list", "image" -> contentType;
            default -> "text";
        };
    }

    private static Integer minPage(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.min(first, second);
    }

    private static Integer maxPage(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.max(first, second);
    }

    private static String overlapText(StringBuilder current, int overlap) {
        if (overlap <= 0) {
            return "";
        }
        String value = current.toString().strip();
        int start = Math.max(0, value.length() - overlap);
        return value.substring(start);
    }

    private static PageRange pageRangeForChild(String text, Integer fallbackPage, SectionBuffer section) {
        Integer start = null;
        Integer end = null;
        Matcher matcher = CHUNK_PAGE_MARKER.matcher(text);
        while (matcher.find()) {
            int page = Integer.parseInt(matcher.group(1));
            if (start == null) {
                start = page;
            }
            end = page;
        }
        if (start != null) {
            return new PageRange(start, end, end);
        }
        if (fallbackPage != null) {
            return new PageRange(fallbackPage, fallbackPage, fallbackPage);
        }
        Integer sectionFallback = section.pageStart != null ? section.pageStart : section.pageEnd;
        return new PageRange(sectionFallback, sectionFallback, sectionFallback);
    }

    static List<RagChunk> splitNote(String noteId, String title, String content,
                                    int chunkSize, int chunkOverlap) {
        String safeTitle = title != null && !title.isBlank() ? title.trim() : "Untitled Note";
        String safeContent = content != null ? content : "";
        String allText = safeTitle + "\n" + safeContent;

        List<RagChunk> chunks = new ArrayList<>();
        if (allText.length() <= NOTE_SHORT_TEXT_MAX_CHARS) {
            RagChunk chunk = new RagChunk();
            chunk.setChunkIndex(0);
            chunk.setContent(buildNoteContent(safeTitle, safeTitle, safeContent));
            chunk.setRetrievalText(buildRetrievalText(safeTitle, safeTitle, "text", safeContent));
            chunk.setContentType(detectContentType(safeContent));
            chunk.setSectionPath(safeTitle);
            chunks.add(chunk);
            assignNoteNeighbors(noteId, chunks);
            return chunks;
        }

        List<SectionBuffer> sections = parseNoteSections(safeTitle, safeContent);
        for (SectionBuffer section : sections) {
            appendNoteSectionChunks(chunks, safeTitle, section, chunkSize, chunkOverlap);
        }
        assignNoteNeighbors(noteId, chunks);
        return chunks;
    }

    static List<RagChunk> fromPlainChunks(List<String> chunks) {
        List<RagChunk> result = new ArrayList<>();
        if (chunks == null) {
            return result;
        }
        for (int i = 0; i < chunks.size(); i++) {
            result.add(RagChunk.fromContent(i, chunks.get(i)));
        }
        return result;
    }

    static List<String> validate(List<RagChunk> chunks, int chunkSize, boolean requirePage) {
        List<String> warnings = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            warnings.add("no chunks generated");
            return warnings;
        }
        int maxContentChars = Math.max(chunkSize * 4, 1000);
        for (RagChunk chunk : chunks) {
            if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                warnings.add("empty chunk: index=" + chunk.getChunkIndex());
            }
            if (chunk.getContent() != null && chunk.getContent().length() > maxContentChars) {
                warnings.add("oversized chunk: index=" + chunk.getChunkIndex()
                        + ", chars=" + chunk.getContent().length());
            }
            if (requirePage && chunk.getPageStart() == null) {
                warnings.add("missing page: index=" + chunk.getChunkIndex());
            }
        }
        return warnings;
    }

    private static List<SectionBuffer> parseKnowledgeSections(String text) {
        List<SectionBuffer> sections = new ArrayList<>();
        SectionBuffer current = new SectionBuffer("正文");
        sections.add(current);

        Integer currentPage = null;
        String[] lines = (text != null ? text : "").split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String line = rawLine.strip();
            Matcher pageMatcher = PDF_PAGE_MARKER.matcher(line);
            if (pageMatcher.matches()) {
                currentPage = Integer.parseInt(pageMatcher.group(1));
                current.markPage(currentPage);
                current.text.append("\n[Page ").append(currentPage).append("]\n");
                continue;
            }

            if (line.isBlank()) {
                current.text.append('\n');
                continue;
            }

            boolean previousBoundary = i == 0 || isBlankOrPageMarker(lines[i - 1]);
            boolean nextBoundary = i == lines.length - 1 || isBlankOrPageMarker(lines[i + 1]);
            if (looksLikeHeading(line, previousBoundary, nextBoundary)) {
                if (!current.isEmpty()) {
                    current = new SectionBuffer(cleanHeading(line));
                    sections.add(current);
                } else {
                    current.sectionPath = cleanHeading(line);
                }
                current.markPage(currentPage);
                current.text.append(line).append('\n');
                continue;
            }

            current.markPage(currentPage);
            current.text.append(rawLine).append('\n');
        }
        return sections.stream().filter(section -> !section.isEmpty()).toList();
    }

    private static List<SectionBuffer> parseNoteSections(String title, String content) {
        List<SectionBuffer> sections = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        headingStack.add(title);

        SectionBuffer current = new SectionBuffer(title);
        sections.add(current);

        String[] lines = content.split("\\R", -1);
        for (String rawLine : lines) {
            String line = rawLine.strip();
            Matcher headingMatcher = MARKDOWN_HEADING.matcher(line);
            if (headingMatcher.matches()) {
                int level = headingMatcher.group(1).length();
                String heading = headingMatcher.group(2).trim();
                while (headingStack.size() > level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                while (headingStack.size() < level) {
                    headingStack.add(heading);
                }
                headingStack.set(level - 1, heading);
                String sectionPath = String.join(" > ", headingStack);
                current = new SectionBuffer(sectionPath);
                sections.add(current);
                current.text.append(rawLine).append('\n');
                continue;
            }

            current.text.append(rawLine).append('\n');
        }
        return sections.stream().filter(section -> !section.isEmpty()).toList();
    }

    private static void appendNoteSectionChunks(List<RagChunk> chunks, String title,
                                                SectionBuffer section, int chunkSize, int chunkOverlap) {
        String sectionText = section.text.toString().strip();
        if (sectionText.isBlank()) {
            return;
        }

        for (String block : splitNoteBlocks(sectionText)) {
            if (block == null || block.isBlank()) {
                continue;
            }
            String contentType = detectContentType(block);
            List<String> pieces = shouldKeepBlockTogether(contentType, block, chunkSize)
                    ? List.of(block)
                    : TextChunker.split(block, chunkSize, chunkOverlap);

            for (String piece : pieces) {
                if (piece == null || piece.isBlank()) {
                    continue;
                }
                RagChunk chunk = new RagChunk();
                chunk.setChunkIndex(chunks.size());
                chunk.setContent(buildNoteContent(title, section.sectionPath, piece));
                chunk.setRetrievalText(buildRetrievalText(title, section.sectionPath, contentType, piece));
                chunk.setContentType(contentType);
                chunk.setSectionPath(section.sectionPath);
                chunks.add(chunk);
            }
        }
    }

    private static List<String> splitNoteBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCode = false;

        for (String line : text.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                if (!inCode && current.length() > 0) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                inCode = !inCode;
                current.append(line).append('\n');
                if (!inCode) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            if (!inCode && trimmed.isBlank()) {
                if (current.length() > 0) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private static boolean shouldKeepBlockTogether(String contentType, String block, int chunkSize) {
        if ("code".equals(contentType) || "table".equals(contentType) || "list".equals(contentType)) {
            return block.length() <= Math.max(chunkSize * 3, 900);
        }
        return false;
    }

    private static void assignNoteNeighbors(String noteId, List<RagChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) {
                chunks.get(i).setPreviousChunkId(noteId + "_" + (i - 1));
            }
            if (i < chunks.size() - 1) {
                chunks.get(i).setNextChunkId(noteId + "_" + (i + 1));
            }
        }
    }

    private static String buildKnowledgeContent(String filename, String sectionPath,
                                                Integer pageStart, Integer pageEnd,
                                                String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("[文件: ").append(filename).append("]\n");
        if (sectionPath != null && !sectionPath.isBlank()) {
            sb.append("[章节: ").append(sectionPath).append("]\n");
        }
        if (pageStart != null) {
            sb.append("[页码: ").append(pageStart);
            if (pageEnd != null && !pageEnd.equals(pageStart)) {
                sb.append("-").append(pageEnd);
            }
            sb.append("]\n");
        }
        sb.append(text.strip());
        return sb.toString();
    }

    private static String buildNoteContent(String title, String sectionPath, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("[笔记: ").append(title).append("]\n");
        if (sectionPath != null && !sectionPath.isBlank()) {
            sb.append("[章节: ").append(sectionPath).append("]\n");
        }
        sb.append(text.strip());
        return sb.toString();
    }

    private static String buildRetrievalText(String title, String sectionPath,
                                             String contentType, String text) {
        return "标题: " + title + "\n"
                + "章节: " + (sectionPath == null ? "" : sectionPath) + "\n"
                + "类型: " + contentType + "\n"
                + "正文: " + text.strip();
    }

    private static boolean looksLikeHeading(String line, boolean previousBoundary, boolean nextBoundary) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String text = line.strip();
        if (text.length() > 100 || text.startsWith("http://") || text.startsWith("https://")) {
            return false;
        }
        if (MARKDOWN_HEADING.matcher(text).matches() || NUMBERED_HEADING.matcher(text).matches()) {
            return true;
        }
        if ((text.endsWith("?") || text.endsWith("？"))
                && text.length() <= 90
                && !text.contains("，")
                && !text.contains(",")) {
            return true;
        }
        return previousBoundary && nextBoundary && looksLikeStandaloneTitle(text);
    }

    private static boolean isBlankOrPageMarker(String rawLine) {
        String line = rawLine == null ? "" : rawLine.strip();
        return line.isBlank() || PDF_PAGE_MARKER.matcher(line).matches();
    }

    private static boolean looksLikeStandaloneTitle(String text) {
        if (text.length() < 2 || text.length() > 50) {
            return false;
        }
        if (text.matches(".*\\d+\\s*(题|w|W|万|字).*")) {
            return false;
        }
        if (text.contains("，") || text.contains(",") || text.contains("。")
                || text.contains("；") || text.contains(";")) {
            return false;
        }
        return !(text.endsWith(".") || text.endsWith("!") || text.endsWith("！"));
    }

    private record PageRange(Integer start, Integer end, Integer lastKnownPage) {
    }

    private record KnowledgeChild(String text, String contentType, Integer pageStart, Integer pageEnd) {
        private KnowledgeChild strip() {
            return new KnowledgeChild(text.strip(), normalizeContentType(contentType), pageStart, pageEnd);
        }

        private KnowledgeChild merge(KnowledgeChild other) {
            return new KnowledgeChild(
                    text.strip() + "\n\n" + other.text().strip(),
                    mergeContentType(contentType, other.contentType()),
                    minPage(pageStart, other.pageStart()),
                    maxPage(pageEnd, other.pageEnd()));
        }
    }

    private static String cleanHeading(String line) {
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        if (markdown.matches()) {
            return markdown.group(2).trim();
        }
        return line.strip();
    }

    private static String detectContentType(String text) {
        if (text == null || text.isBlank()) {
            return "text";
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("```") || trimmed.matches("(?s).*(public|private|class|def|function|SELECT|CREATE TABLE).*")) {
            return "code";
        }
        String[] lines = trimmed.split("\\R");
        int listLines = 0;
        int tableLines = 0;
        for (String line : lines) {
            String s = line.strip();
            if (s.matches("^([-*+]|\\d+[.)])\\s+.*") || s.matches("^- \\[[ xX]]\\s+.*")) {
                listLines++;
            }
            if (s.contains("|")) {
                tableLines++;
            }
        }
        if (tableLines >= 2) {
            return "table";
        }
        if (listLines >= 2) {
            return "list";
        }
        return "text";
    }

    private static final class SectionBuffer {
        private String sectionPath;
        private final StringBuilder text = new StringBuilder();
        private final List<DocumentBlock> blocks = new ArrayList<>();
        private Integer pageStart;
        private Integer pageEnd;

        private SectionBuffer(String sectionPath) {
            this.sectionPath = sectionPath;
        }

        private void markPage(Integer page) {
            if (page == null) {
                return;
            }
            if (pageStart == null || page < pageStart) {
                pageStart = page;
            }
            if (pageEnd == null || page > pageEnd) {
                pageEnd = page;
            }
        }

        private void addBlock(DocumentBlock block) {
            if (block == null || block.type() == DocumentBlock.Type.PAGE_BREAK) {
                return;
            }
            blocks.add(block);
            if (block.markdown() != null && !block.markdown().isBlank()) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(block.markdown().strip());
            }
            markPage(block.pageStart());
            markPage(block.pageEnd());
        }

        private boolean isEmpty() {
            return blocks.isEmpty() && text.toString().replaceAll("(?m)^\\[Page \\d+]$", "").isBlank();
        }
    }
}
