package com.rag.notebook.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class MarkdownBlockParser {

    private static final Pattern PDF_PAGE_MARKER = Pattern.compile("^\\[Page\\s+(\\d+)/(\\d+)]$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(\\d+(\\.\\d+)*[\\s.．、]+|[一二三四五六七八九十]+[、.．]+|第.{1,12}[章节篇])[\\s\\S]{1,90}$");
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*([-*+]|\\d+[.)]|[一二三四五六七八九十]+[、.．])\\s+.+$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");

    private MarkdownBlockParser() {
    }

    static List<DocumentBlock> parse(String text) {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        String[] lines = (text != null ? text : "").split("\\R", -1);
        Integer currentPage = null;

        for (int i = 0; i < lines.length; ) {
            String rawLine = lines[i];
            String line = rawLine.strip();

            Matcher pageMatcher = PDF_PAGE_MARKER.matcher(line);
            if (pageMatcher.matches()) {
                currentPage = Integer.parseInt(pageMatcher.group(1));
                blocks.add(new DocumentBlock(DocumentBlock.Type.PAGE_BREAK, "", "",
                        0, headingStack, currentPage, currentPage, "page_break"));
                i++;
                continue;
            }

            if (line.isBlank()) {
                i++;
                continue;
            }

            if (line.startsWith("```")) {
                ParseResult code = consumeFencedCode(lines, i, currentPage, headingStack);
                blocks.add(code.block());
                i = code.nextIndex();
                continue;
            }

            boolean previousBoundary = i == 0 || isBoundary(lines[i - 1]);
            boolean nextBoundary = i == lines.length - 1 || isBoundary(lines[i + 1]);
            Heading heading = parseHeading(line, previousBoundary, nextBoundary);
            if (heading != null) {
                headingStack = updateHeadingStack(headingStack, heading.level(), heading.title());
                String markdown = "#".repeat(Math.max(1, Math.min(6, heading.level()))) + " " + heading.title();
                blocks.add(new DocumentBlock(DocumentBlock.Type.HEADING, heading.title(), markdown,
                        heading.level(), headingStack, currentPage, currentPage, "heading"));
                i++;
                continue;
            }

            if (isMarkdownTableStart(lines, i)) {
                ParseResult table = consumeMarkdownTable(lines, i, currentPage, headingStack);
                blocks.add(table.block());
                i = table.nextIndex();
                continue;
            }

            if (isPdfTableStart(lines, i)) {
                ParseResult table = consumePdfTable(lines, i, currentPage, headingStack);
                blocks.add(table.block());
                i = table.nextIndex();
                continue;
            }

            if (LIST_LINE.matcher(rawLine).matches()) {
                ParseResult list = consumeList(lines, i, currentPage, headingStack);
                blocks.add(list.block());
                i = list.nextIndex();
                continue;
            }

            if (isCodeLikeStart(lines, i)) {
                ParseResult code = consumeCodeLike(lines, i, currentPage, headingStack);
                blocks.add(code.block());
                i = code.nextIndex();
                continue;
            }

            ParseResult paragraph = consumeParagraph(lines, i, currentPage, headingStack);
            blocks.add(paragraph.block());
            i = paragraph.nextIndex();
        }

        return blocks.stream()
                .filter(block -> block.type() == DocumentBlock.Type.PAGE_BREAK || block.hasText())
                .collect(Collectors.toList());
    }

    private static ParseResult consumeFencedCode(String[] lines, int start, Integer page, List<String> headingPath) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        sb.append(lines[i]).append('\n');
        i++;
        while (i < lines.length) {
            sb.append(lines[i]).append('\n');
            if (lines[i].strip().startsWith("```")) {
                i++;
                break;
            }
            i++;
        }
        String markdown = sb.toString().strip();
        return new ParseResult(new DocumentBlock(DocumentBlock.Type.CODE, markdown, markdown,
                0, headingPath, page, page, "code"), i);
    }

    private static ParseResult consumeMarkdownTable(String[] lines, int start, Integer page, List<String> headingPath) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < lines.length && isPipeTableLine(lines[i])) {
            sb.append(lines[i].strip()).append('\n');
            i++;
        }
        String markdown = sb.toString().strip();
        return new ParseResult(new DocumentBlock(DocumentBlock.Type.TABLE, markdown, markdown,
                0, headingPath, page, page, "table"), i);
    }

    private static ParseResult consumePdfTable(String[] lines, int start, Integer page, List<String> headingPath) {
        List<List<String>> rows = new ArrayList<>();
        int i = start;
        while (i < lines.length && isPdfTableLine(lines[i])) {
            rows.add(splitTableColumns(lines[i]));
            i++;
        }
        int maxColumns = rows.stream().mapToInt(List::size).max().orElse(0);
        String markdown = toMarkdownTable(rows, maxColumns);
        String text = rows.stream()
                .map(row -> String.join(" | ", row))
                .collect(Collectors.joining("\n"));
        return new ParseResult(new DocumentBlock(DocumentBlock.Type.TABLE, text, markdown,
                0, headingPath, page, page, "table"), i);
    }

    private static ParseResult consumeList(String[] lines, int start, Integer page, List<String> headingPath) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < lines.length && LIST_LINE.matcher(lines[i]).matches()) {
            sb.append(lines[i].strip()).append('\n');
            i++;
        }
        String markdown = sb.toString().strip();
        return new ParseResult(new DocumentBlock(DocumentBlock.Type.LIST, markdown, markdown,
                0, headingPath, page, page, "list"), i);
    }

    private static ParseResult consumeCodeLike(String[] lines, int start, Integer page, List<String> headingPath) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < lines.length && !isBoundary(lines[i]) && isCodeLikeLine(lines[i])) {
            sb.append(lines[i]).append('\n');
            i++;
        }
        String code = sb.toString().strip();
        String markdown = "```" + detectLanguage(code) + "\n" + code + "\n```";
        return new ParseResult(new DocumentBlock(DocumentBlock.Type.CODE, code, markdown,
                0, headingPath, page, page, "code"), i);
    }

    private static ParseResult consumeParagraph(String[] lines, int start, Integer page, List<String> headingPath) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < lines.length) {
            String line = lines[i].strip();
            if (line.isBlank() || PDF_PAGE_MARKER.matcher(line).matches()) {
                break;
            }
            boolean previousBoundary = i == 0 || isBoundary(lines[i - 1]);
            boolean nextBoundary = i == lines.length - 1 || isBoundary(lines[i + 1]);
            if (i > start && (parseHeading(line, previousBoundary, nextBoundary) != null
                    || LIST_LINE.matcher(lines[i]).matches()
                    || isMarkdownTableStart(lines, i)
                    || isPdfTableStart(lines, i)
                    || isCodeLikeStart(lines, i))) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i].strip());
            i++;
        }
        String paragraph = sb.toString().strip();
        return new ParseResult(new DocumentBlock(DocumentBlock.Type.PARAGRAPH, paragraph, paragraph,
                0, headingPath, page, page, "text"), Math.max(i, start + 1));
    }

    private static Heading parseHeading(String line, boolean previousBoundary, boolean nextBoundary) {
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), markdown.group(2).trim());
        }
        if (line.length() > 100 || line.startsWith("http://") || line.startsWith("https://")) {
            return null;
        }
        Matcher numbered = NUMBERED_HEADING.matcher(line);
        if (numbered.matches()) {
            return new Heading(headingLevelFromNumbering(line), line.strip());
        }
        if ((line.endsWith("?") || line.endsWith("？")) && line.length() <= 90
                && !line.contains("，") && !line.contains(",")) {
            return new Heading(3, line.strip());
        }
        if (previousBoundary && nextBoundary && looksLikeStandaloneTitle(line)) {
            return new Heading(2, line.strip());
        }
        return null;
    }

    private static List<String> updateHeadingStack(List<String> current, int level, String title) {
        List<String> next = new ArrayList<>(current);
        int index = Math.max(0, Math.min(level - 1, 5));
        while (next.size() > index) {
            next.remove(next.size() - 1);
        }
        next.add(title);
        return next;
    }

    private static int headingLevelFromNumbering(String line) {
        Matcher numeric = Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(line);
        if (numeric.find()) {
            return Math.min(6, numeric.group(1).split("\\.").length);
        }
        if (line.startsWith("第")) {
            return 1;
        }
        return 2;
    }

    private static boolean looksLikeStandaloneTitle(String text) {
        String line = text == null ? "" : text.strip();
        if (line.length() < 2 || line.length() > 50) {
            return false;
        }
        if (line.matches(".*\\d+\\s*(题|w|W|万|字).*")) {
            return false;
        }
        if (line.contains("，") || line.contains(",") || line.contains("。")
                || line.contains("；") || line.contains(";")) {
            return false;
        }
        return !(line.endsWith(".") || line.endsWith("!") || line.endsWith("！"));
    }

    private static boolean isBoundary(String rawLine) {
        String line = rawLine == null ? "" : rawLine.strip();
        return line.isBlank() || PDF_PAGE_MARKER.matcher(line).matches();
    }

    private static boolean isMarkdownTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length) {
            return false;
        }
        return isPipeTableLine(lines[index]) && TABLE_SEPARATOR.matcher(lines[index + 1].strip()).matches();
    }

    private static boolean isPipeTableLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine.strip();
        return line.contains("|") && !line.startsWith("http://") && !line.startsWith("https://");
    }

    private static boolean isPdfTableStart(String[] lines, int index) {
        return index + 1 < lines.length && isPdfTableLine(lines[index]) && isPdfTableLine(lines[index + 1]);
    }

    private static boolean isPdfTableLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine.strip();
        if (line.isBlank() || line.length() > 160 || line.contains("。") || line.contains("，")) {
            return false;
        }
        return splitTableColumns(line).size() >= 2;
    }

    private static List<String> splitTableColumns(String rawLine) {
        String line = rawLine == null ? "" : rawLine.strip();
        if (line.contains("|")) {
            String[] parts = line.replaceAll("^\\|", "").replaceAll("\\|$", "").split("\\|");
            return normalizeCells(parts);
        }
        return normalizeCells(line.split("\\s{2,}|\\t+"));
    }

    private static List<String> normalizeCells(String[] parts) {
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            String cell = part == null ? "" : part.strip();
            if (!cell.isBlank()) {
                cells.add(cell.replace("|", "\\|"));
            }
        }
        return cells;
    }

    private static String toMarkdownTable(List<List<String>> rows, int maxColumns) {
        if (rows.isEmpty() || maxColumns == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendTableRow(sb, rows.get(0), maxColumns);
        appendSeparatorRow(sb, maxColumns);
        for (int i = 1; i < rows.size(); i++) {
            appendTableRow(sb, rows.get(i), maxColumns);
        }
        return sb.toString().strip();
    }

    private static void appendTableRow(StringBuilder sb, List<String> row, int maxColumns) {
        sb.append("| ");
        for (int i = 0; i < maxColumns; i++) {
            sb.append(i < row.size() ? row.get(i) : "");
            sb.append(" | ");
        }
        sb.append('\n');
    }

    private static void appendSeparatorRow(StringBuilder sb, int maxColumns) {
        sb.append("| ");
        for (int i = 0; i < maxColumns; i++) {
            sb.append("--- | ");
        }
        sb.append('\n');
    }

    private static boolean isCodeLikeStart(String[] lines, int index) {
        if (!isCodeLikeLine(lines[index])) {
            return false;
        }
        return index + 1 < lines.length && isCodeLikeLine(lines[index + 1]);
    }

    private static boolean isCodeLikeLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine;
        String trimmed = line.strip();
        if (trimmed.isBlank()) {
            return false;
        }
        if (line.startsWith("    ") || line.startsWith("\t")) {
            return true;
        }
        if (trimmed.startsWith("//") || trimmed.startsWith("@") || trimmed.endsWith(";")
                || trimmed.endsWith("{") || trimmed.endsWith("}")) {
            return true;
        }
        return trimmed.matches(".*\\b(public|private|protected|class|interface|enum|static|void|return|if|else|for|while|try|catch|finally|new|import|package|SELECT|CREATE|INSERT|UPDATE|DELETE)\\b.*");
    }

    private static String detectLanguage(String code) {
        String lower = code.toLowerCase();
        if (lower.contains("select ") || lower.contains("create table")) {
            return "sql";
        }
        if (lower.contains("public ") || lower.contains("class ") || lower.contains("package ")) {
            return "java";
        }
        return "";
    }

    private record Heading(int level, String title) {
    }

    private record ParseResult(DocumentBlock block, int nextIndex) {
    }
}
