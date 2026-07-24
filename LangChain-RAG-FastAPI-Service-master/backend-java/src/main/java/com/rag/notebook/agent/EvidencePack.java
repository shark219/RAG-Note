package com.rag.notebook.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 AgentState 中提取的干净证据包。
 *
 * 关键设计：与 Agent 内部消息历史隔离。
 * Composer 只看到 userQuery + 结构化证据 + 产物，看不到 System Prompt、Reflection 等内部上下文。
 *
 * 新增 artifacts 字段：产物类工具（generateMindMap、generateDiagram）的结果，
 * 让 Composer 能正确识别"产物已生成"，而不是误判为"没有证据"。
 */
public record EvidencePack(
        String userQuery,
        List<NoteEvidence> notes,
        String knowledgeBaseSummary,
        String noteStats,
        String todayReviews,
        String writeConfirmation,
        String goal,
        List<Artifact> artifacts
) {

    /**
     * 从 AgentState 提取结构化证据。
     */
    public static EvidencePack from(AgentState state) {
        List<NoteEvidence> notes = extractNoteEvidences(state);
        String kbSummary = extractKnowledgeBaseSummary(state);
        String stats = extractNoteStats(state);
        String reviews = extractTodayReviews(state);
        String writeConf = state.getWriteConfirmation();
        String goal = state.getGoal();
        List<Artifact> artifacts = state.getArtifacts();

        return new EvidencePack(state.getOriginalQuery(), notes, kbSummary, stats, reviews, writeConf, goal, artifacts);
    }

    /**
     * 是否有任何有效证据（包括产物）
     */
    public boolean isEmpty() {
        return notes.isEmpty() && knowledgeBaseSummary == null
                && noteStats == null && todayReviews == null
                && writeConfirmation == null && artifacts.isEmpty();
    }

    /** 是否包含产物（思维导图、图表等） */
    public boolean hasArtifacts() {
        return artifacts != null && !artifacts.isEmpty();
    }

    // === 笔记证据提取 ===

    private static List<NoteEvidence> extractNoteEvidences(AgentState state) {
        List<NoteEvidence> raw = new ArrayList<>();

        for (AgentState.ToolCallRecord r : state.getToolHistory()) {
            if (r.quality() != AgentState.ResultQuality.GOOD) continue;

            switch (r.toolName()) {
                case "getNote" -> {
                    NoteEvidence e = parseGetNoteResult(r.result());
                    if (e != null) raw.add(e);
                }
                case "searchNotes" -> raw.addAll(parseSearchNotesResult(r.result()));
                case "listNotes" -> raw.addAll(parseListNotesResult(r.result()));
            }
        }

        return deduplicateByDepth(raw);
    }

    /**
     * 去重：同一 noteId 只保留深度最高的证据。
     * CONTENT（getNote 完整正文）> SEARCH（searchNotes 预览）> LIST（listNotes 摘要）
     */
    private static List<NoteEvidence> deduplicateByDepth(List<NoteEvidence> raw) {
        // 使用 LinkedHashSet 保持首次出现顺序，但按深度替换
        List<NoteEvidence> result = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        // 先收集所有 noteId
        for (NoteEvidence e : raw) {
            if (e.noteId() != null && !e.noteId().isBlank()) {
                seenIds.add(e.noteId());
            }
        }

        // 对每个 noteId，选深度最高的
        for (String noteId : seenIds) {
            NoteEvidence best = null;
            for (NoteEvidence e : raw) {
                if (noteId.equals(e.noteId())) {
                    if (best == null || e.depth().ordinal() > best.depth().ordinal()) {
                        best = e;
                    }
                }
            }
            if (best != null) {
                result.add(best);
            }
        }

        // 保留无 noteId 的证据（如 listNotes 中未提取到 ID 的条目）
        for (NoteEvidence e : raw) {
            if (e.noteId() == null || e.noteId().isBlank()) {
                result.add(e);
            }
        }

        return result;
    }

    // === 解析器 ===

    /**
     * 解析 getNote 返回的完整笔记内容。
     * 格式：# 标题\n\n分类：...\n标签：...\n创建：...  更新：...\n---\n\n正文\n\n[重要]...
     */
    private static NoteEvidence parseGetNoteResult(String result) {
        if (result == null) return null;
        try {
            String title = extractFirst(result, "^# (.+)$");
            String noteId = null; // getNote 结果不直接包含 ID，从工作记忆中获取
            String category = extractFirst(result, "^分类：(.+)$");
            String tags = extractFirst(result, "^标签：(.+)$");

            // 提取正文：从 "---" 之后到 "[重要]" 之前
            String content = "";
            int contentStart = result.indexOf("---\n\n");
            if (contentStart >= 0) {
                contentStart += 5;
                int contentEnd = result.indexOf("\n\n[重要]", contentStart);
                if (contentEnd < 0) contentEnd = result.length();
                content = result.substring(contentStart, contentEnd).trim();
            } else {
                content = result;
            }

            return new NoteEvidence(noteId, title, content, category, tags,
                    AgentState.EvidenceLevel.CONTENT_EVIDENCE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 searchNotes 返回的搜索结果。
     * 格式：找到以下相关笔记：\n1. 标题 [ID: xxx] [分类]\n   内容: 预览\n
     */
    private static List<NoteEvidence> parseSearchNotesResult(String result) {
        List<NoteEvidence> list = new ArrayList<>();
        if (result == null || result.isBlank()) return list;

        // 按笔记条目分割
        String[] parts = result.split("\n(?=\\d+\\. )");
        for (String part : parts) {
            String title = extractFirst(part, "^\\d+\\. (.+?) \\[ID:");
            String noteId = extractFirst(part, "\\[ID: ([^\\]]+)\\]");
            String category = extractFirst(part, "\\[([^\\]]+)\\]\\s*(?:标签|$)");
            if (category != null && category.startsWith("ID:")) category = null;
            String contentPreview = extractFirst(part, "内容:\\s*(.+)");

            if (title != null) {
                list.add(new NoteEvidence(noteId, title.trim(), contentPreview,
                        category, null, AgentState.EvidenceLevel.SEARCH_EVIDENCE));
            }
        }
        return list;
    }

    /**
     * 解析 listNotes 返回的笔记列表。
     * 格式：你的笔记列表（共 N 篇）：\n\n1. 标题 [ID: xxx] [分类]\n   摘要：预览\n
     */
    private static List<NoteEvidence> parseListNotesResult(String result) {
        List<NoteEvidence> list = new ArrayList<>();
        if (result == null || result.isBlank()) return list;

        String[] parts = result.split("\n(?=\\d+\\. )");
        for (String part : parts) {
            String title = extractFirst(part, "^\\d+\\. (.+?) \\[ID:");
            String noteId = extractFirst(part, "\\[ID: ([^\\]]+)\\]");
            String category = extractFirst(part, "\\[([^\\]]+)\\]");
            if (category != null && category.startsWith("ID:")) category = null;
            String summary = extractFirst(part, "摘要：(.+)");

            if (title != null) {
                list.add(new NoteEvidence(noteId, title.trim(), summary,
                        category, null, AgentState.EvidenceLevel.LIST_EVIDENCE));
            }
        }
        return list;
    }

    // === 其他证据类型 ===

    private static String extractKnowledgeBaseSummary(AgentState state) {
        for (AgentState.ToolCallRecord r : state.getToolHistory()) {
            if ("ragSummary".equals(r.toolName()) && r.quality() == AgentState.ResultQuality.GOOD) {
                return r.result();
            }
        }
        return null;
    }

    private static String extractNoteStats(AgentState state) {
        for (AgentState.ToolCallRecord r : state.getToolHistory()) {
            if ("getNoteStats".equals(r.toolName()) && r.quality() == AgentState.ResultQuality.GOOD) {
                return r.result();
            }
        }
        return null;
    }

    private static String extractTodayReviews(AgentState state) {
        for (AgentState.ToolCallRecord r : state.getToolHistory()) {
            if ("getTodayReviews".equals(r.toolName()) && r.quality() == AgentState.ResultQuality.GOOD) {
                return r.result();
            }
        }
        return null;
    }

    // === 工具方法 ===

    private static String extractFirst(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    // === NoteEvidence 记录 ===

    /**
     * 单条笔记证据。
     *
     * @param noteId   内部 ID（Composer 默认不暴露给用户，除非用户明确问）
     * @param title    笔记标题
     * @param content  笔记内容（可能是完整正文、预览或摘要）
     * @param category 分类
     * @param tags     标签
     * @param depth    证据深度（CONTENT > SEARCH > LIST）
     */
    public record NoteEvidence(
            String noteId,
            String title,
            String content,
            String category,
            String tags,
            AgentState.EvidenceLevel depth
    ) {}
}
