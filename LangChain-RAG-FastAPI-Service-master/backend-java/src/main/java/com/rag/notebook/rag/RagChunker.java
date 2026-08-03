package com.rag.notebook.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RagChunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    // 纯分隔线（---、***、___、- - -），和 MarkdownBlockParser 保持一致，跳过而不是当成正文/列表项
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final int NOTE_SHORT_TEXT_MAX_CHARS = 600;

    private RagChunker() {
    }

    /**
     * 知识库文档切片：按中文标点层级递归切分为固定大小的扁平 chunk（无父子/章节结构）。
     * 章节检测（标题识别）不可靠，容易产生错误的 section 归属，因此不再用它切分边界；
     * 仅保留 code/table/list 整块不拆和 PDF 页码识别这两个独立、可靠的能力。
     * isPdf=true 时关闭标题启发式识别（PDF 纯文本没有可靠的标题标记，容易误判并把
     * 猜测出的标题包装成多余的 "#"/"##" 混入正文）。
     */
    static List<RagChunk> splitKnowledge(String text, int chunkSize, int chunkOverlap, boolean isPdf) {
        List<DocumentBlock> blocks = MarkdownBlockParser.parse(text, isPdf);
        List<KnowledgeChild> pieces = buildKnowledgeChildren(blocks, chunkSize, chunkOverlap);

        List<RagChunk> chunks = new ArrayList<>();
        for (KnowledgeChild piece : pieces) {
            if (piece == null || piece.text().isBlank()) {
                continue;
            }
            RagChunk chunk = new RagChunk();
            chunk.setChunkIndex(chunks.size());
            // content 只存干净正文；文件名/页码等来源信息由生成阶段（VectorStoreService.joinKnowledgeChunks）
            // 结合 KnowledgeDocument/KnowledgeDocumentChunk 的独立字段动态拼接，不再预先烧进存储内容。
            chunk.setContent(piece.text().strip());
            chunk.setRetrievalText(piece.text());
            chunk.setContentType(piece.contentType());
            chunk.setPageStart(piece.pageStart());
            chunk.setPageEnd(piece.pageEnd());
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 在扁平的 block 流上做切分：普通文本按 targetChars/overlap 累积，
     * code/table/list 尽量整块保留；HEADING 类型的 block 不再触发新的分组，
     * 直接作为普通文本并入当前累积段（即使标题识别有误也不会影响切片边界）。
     */
    private static List<KnowledgeChild> buildKnowledgeChildren(List<DocumentBlock> blocks, int targetChars, int overlap) {
        List<KnowledgeChild> children = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentType = "text";
        Integer pageStart = null;
        Integer pageEnd = null;

        for (DocumentBlock block : blocks) {
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
                // 用和 shouldKeepKnowledgeBlockTogether 相同的阈值判断是否要拆，避免两处阈值不一致导致
                // 本应整块保留的 table/code/list 又被按字符硬切（表格被从单元格中间切断）。
                if (blockText.length() <= knowledgeKeepTogetherLimit(targetChars)) {
                    children.add(new KnowledgeChild(blockText, blockType, block.pageStart(), block.pageEnd()));
                } else {
                    children.addAll(splitLargeKnowledgeBlockByLine(blockText, blockType, block.pageStart(), block.pageEnd(),
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
                // code/table/list 超限时按行切，保证不会在表格行/代码行中间断开；
                // 普通文本仍走字符级的 TextChunker.split（标点感知切分更适合自然语言）。
                if ("code".equals(blockType) || "table".equals(blockType) || "list".equals(blockType)) {
                    children.addAll(splitLargeKnowledgeBlockByLine(blockText, blockType, block.pageStart(), block.pageEnd(),
                            targetChars, overlap));
                } else {
                    children.addAll(splitLargeKnowledgeBlock(blockText, blockType, block.pageStart(), block.pageEnd(),
                            targetChars, overlap));
                }
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
        if ("code".equals(contentType) || "table".equals(contentType) || "list".equals(contentType)) {
            return splitLargeKnowledgeBlockByLine(text, contentType, pageStart, pageEnd, targetChars, overlap);
        }
        List<KnowledgeChild> children = new ArrayList<>();
        for (String piece : TextChunker.split(text, targetChars, overlap)) {
            if (!piece.isBlank()) {
                children.add(new KnowledgeChild(piece, contentType, pageStart, pageEnd));
            }
        }
        return children;
    }

    /**
     * 按行（而不是字符）切分超长的 table/code/list block，保证每个 chunk 内的表格行/代码行完整，
     * 不会在单元格或代码语句中间断开。单行本身超过 targetChars 时才不得已按字符兜底切分。
     */
    private static List<KnowledgeChild> splitLargeKnowledgeBlockByLine(String text, String contentType,
                                                                       Integer pageStart, Integer pageEnd,
                                                                       int targetChars, int overlap) {
        List<KnowledgeChild> children = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            if (current.length() > 0 && current.length() + line.length() + 1 > targetChars) {
                children.add(new KnowledgeChild(current.toString().strip(), contentType, pageStart, pageEnd));
                String carry = overlapByLine(current.toString(), overlap);
                current.setLength(0);
                if (!carry.isBlank() && carry.length() + line.length() + 1 <= targetChars) {
                    current.append(carry).append('\n');
                }
            }
            if (line.length() > targetChars) {
                if (current.length() > 0) {
                    children.add(new KnowledgeChild(current.toString().strip(), contentType, pageStart, pageEnd));
                    current.setLength(0);
                }
                for (String piece : TextChunker.split(line, targetChars, overlap)) {
                    if (!piece.isBlank()) {
                        children.add(new KnowledgeChild(piece, contentType, pageStart, pageEnd));
                    }
                }
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.toString().isBlank()) {
            children.add(new KnowledgeChild(current.toString().strip(), contentType, pageStart, pageEnd));
        }
        return children;
    }

    /**
     * 按整行（而不是字符）截取重叠内容，避免重叠部分正好落在表格行/代码行中间，
     * 导致拼接到下一个 chunk 开头时出现半行残留（如 "0t0 | TCP | LISTEN |" 这种断行）。
     */
    private static String overlapByLine(String text, int overlap) {
        if (overlap <= 0) {
            return "";
        }
        String[] lines = text.strip().split("\n", -1);
        StringBuilder carry = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {
            String candidate = lines[i] + (carry.length() > 0 ? "\n" + carry : "");
            if (candidate.length() > overlap && carry.length() > 0) {
                break;
            }
            carry = new StringBuilder(candidate);
            if (candidate.length() >= overlap) {
                break;
            }
        }
        return carry.toString();
    }

    private static int knowledgeKeepTogetherLimit(int targetChars) {
        return Math.max(targetChars * 2, 1200);
    }

    private static boolean shouldKeepKnowledgeBlockTogether(String contentType, String text, int targetChars) {
        if ("code".equals(contentType) || "table".equals(contentType) || "list".equals(contentType)) {
            return text.length() <= knowledgeKeepTogetherLimit(targetChars);
        }
        return false;
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

    static List<RagChunk> splitNote(String noteId, String title, String content,
                                    int chunkSize, int chunkOverlap) {
        String safeTitle = title != null && !title.isBlank() ? title.trim() : "Untitled Note";
        String safeContent = content != null ? content : "";
        String allText = safeTitle + "\n" + safeContent;

        List<RagChunk> chunks = new ArrayList<>();
        if (allText.length() <= NOTE_SHORT_TEXT_MAX_CHARS) {
            RagChunk chunk = new RagChunk();
            chunk.setChunkIndex(0);
            // content 只存干净正文；笔记标题/章节等来源信息由生成阶段（VectorStoreService.joinNoteChunks）
            // 结合 Note.title/NoteChunk.sectionPath 动态拼接，不再预先烧进存储内容。
            chunk.setContent(safeContent.strip());
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
                // 标题行只用于生成 sectionPath，不再重复写入正文（sectionPath 已经携带同样的信息）
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
                // content 只存干净正文，标题/章节前缀改为生成阶段动态拼接
                chunk.setContent(piece.strip());
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

            if (!inCode && (trimmed.isBlank() || SEPARATOR_LINE.matcher(trimmed).matches())) {
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

    private static String buildRetrievalText(String title, String sectionPath,
                                             String contentType, String text) {
        return "标题: " + title + "\n"
                + "章节: " + (sectionPath == null ? "" : sectionPath) + "\n"
                + "类型: " + contentType + "\n"
                + "正文: " + text.strip();
    }

    /**
     * 表格/列表行数统计优先于代码关键词匹配：单元格内容里出现 public/private 等词
     * （比如对比 Java 修饰符的表格）不应该抢先把整块判成 code。
     */
    private static String detectContentType(String text) {
        if (text == null || text.isBlank()) {
            return "text";
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
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
        if (trimmed.matches("(?s).*(public|private|class|def|function|SELECT|CREATE TABLE).*")) {
            return "code";
        }
        return "text";
    }

    private record KnowledgeChild(String text, String contentType, Integer pageStart, Integer pageEnd) {
    }

    private static final class SectionBuffer {
        private String sectionPath;
        private final StringBuilder text = new StringBuilder();

        private SectionBuffer(String sectionPath) {
            this.sectionPath = sectionPath;
        }

        private boolean isEmpty() {
            return text.toString().isBlank();
        }
    }
}
