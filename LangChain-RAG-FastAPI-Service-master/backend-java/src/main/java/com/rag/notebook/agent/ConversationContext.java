package com.rag.notebook.agent;

/**
 * 当前会话的笔记上下文 — 追踪"用户正在操作哪篇笔记"。
 *
 * 解决的问题：
 * - "给它生成思维导图" → 知道"它"是哪篇
 * - "这篇笔记讲了什么" → 不需要重新搜索
 * - "继续修改" → 知道在改哪篇
 */
public class ConversationContext {

    private String currentNoteId;
    private String currentNoteTitle;
    private String lastOperation;

    public ConversationContext() {}

    public ConversationContext(String noteId, String title, String operation) {
        this.currentNoteId = noteId;
        this.currentNoteTitle = title;
        this.lastOperation = operation;
    }

    public String getCurrentNoteId() { return currentNoteId; }
    public void setCurrentNoteId(String id) { this.currentNoteId = id; }

    public String getCurrentNoteTitle() { return currentNoteTitle; }
    public void setCurrentNoteTitle(String title) { this.currentNoteTitle = title; }

    public String getLastOperation() { return lastOperation; }
    public void setLastOperation(String op) { this.lastOperation = op; }

    public boolean hasActiveNote() {
        return currentNoteId != null && !currentNoteId.isBlank();
    }

    /**
     * 根据工具调用结果更新上下文
     */
    public void updateFromToolCall(String toolName, String args, String rawResult) {
        switch (toolName) {
            case "getNote" -> {
                // 提取 noteId 和标题
                String noteId = extractArg(args, "noteId");
                String title = extractTitle(rawResult);
                if (noteId != null && !noteId.isBlank()) {
                    this.currentNoteId = noteId;
                    this.currentNoteTitle = title;
                    this.lastOperation = "getNote";
                }
            }
            case "appendNote", "editNote" -> {
                String noteId = extractArg(args, "noteId");
                if (noteId != null && !noteId.isBlank()) {
                    this.currentNoteId = noteId;
                    this.lastOperation = toolName;
                }
            }
            case "createNote" -> {
                // 创建后提取新笔记 ID
                String createdId = extractFirst(rawResult, "ID:\\s*(\\S+)");
                String title = extractFirst(rawResult, "标题:\\s*(.+)");
                if (createdId != null) {
                    this.currentNoteId = createdId;
                    this.currentNoteTitle = title;
                    this.lastOperation = "createNote";
                }
            }
            case "searchNotes", "listNotes" -> {
                // 搜索结果不自动设为当前笔记（用户可能看多篇）
                // 但如果之前没有活跃笔记，记录第一个结果
                if (!hasActiveNote()) {
                    String firstId = extractFirst(rawResult, "\\[ID:\\s*([^\\]]+)\\]");
                    if (firstId != null) {
                        this.currentNoteId = firstId;
                        this.lastOperation = toolName;
                    }
                }
            }
        }
    }

    /**
     * 生成给 LLM 的上下文提示
     */
    public String toContextHint() {
        if (!hasActiveNote()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("[当前笔记上下文]\n");
        sb.append("用户刚才操作的笔记：");
        if (currentNoteTitle != null) {
            sb.append("《").append(currentNoteTitle).append("》");
        }
        sb.append("（noteId=").append(currentNoteId).append("）");
        if (lastOperation != null) {
            sb.append("，上一步操作：").append(lastOperation);
        }
        sb.append("\n");
        sb.append("如果用户用\"这篇\"、\"那个笔记\"、\"它\"等代词指代笔记，就是指这篇。");
        return sb.toString();
    }

    private static String extractArg(String argsJson, String key) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            java.util.Map<String, String> map = AgentService.parseToolArguments(argsJson);
            return map.getOrDefault(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractTitle(String rawResult) {
        return extractFirst(rawResult, "^#\\s*(.+)");
    }

    private static String extractFirst(String text, String regex) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.MULTILINE).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
