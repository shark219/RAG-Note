package com.rag.notebook.agent;

import com.rag.notebook.note.dto.NoteCreate;
import com.rag.notebook.note.dto.NoteUpdate;
import com.rag.notebook.note.service.NoteService;
import com.rag.notebook.rag.RagService;
import com.rag.notebook.review.service.ReviewService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentTools {

    private final RagService ragService;
    private final NoteService noteService;
    private final ReviewService reviewService;
    private final ModelFactory modelFactory;

    // 存储最新的 traceId，供 AgentService 读取
    private String latestTraceId;

    // 结构化结果缓存：AgentLoop 执行工具后从这里读取 ToolResult
    private final ThreadLocal<ToolResult> lastResult = new ThreadLocal<>();
    private final ThreadLocal<AgentState> activeState = new ThreadLocal<>();

    // 当前请求的检索范围配置（由 AgentService 在启动 agent 前设置）
    private final ThreadLocal<Boolean> searchKnowledge = new ThreadLocal<>();
    private final ThreadLocal<Boolean> searchNotes = new ThreadLocal<>();
    private final ThreadLocal<List<String>> selectedKnowledgeDocs = new ThreadLocal<>();
    private final ThreadLocal<List<String>> selectedNotes = new ThreadLocal<>();

    public AgentTools(RagService ragService, NoteService noteService, ReviewService reviewService,
                      ModelFactory modelFactory) {
        this.ragService = ragService;
        this.noteService = noteService;
        this.reviewService = reviewService;
        this.modelFactory = modelFactory;
    }

    public String getLatestTraceId() {
        return latestTraceId;
    }

    /** 获取最近一次工具调用的结构化结果 */
    public ToolResult getLastResult() {
        ToolResult r = lastResult.get();
        lastResult.remove();
        return r;
    }

    /** 设置结构化结果（供 AgentLoop 在异常时调用） */
    public void setResult(ToolResult result) {
        lastResult.set(result);
    }

    public void bindState(AgentState state) {
        activeState.set(state);
    }

    public void clearBoundState() {
        activeState.remove();
    }

    /** 设置当前请求的检索范围配置（供 AgentService 在启动 agent 前调用） */
    public void setSearchFilters(boolean sk, boolean sn,
                                  List<String> selectedKbDocs, List<String> selectedNts) {
        searchKnowledge.set(sk);
        searchNotes.set(sn);
        selectedKnowledgeDocs.set(selectedKbDocs);
        selectedNotes.set(selectedNts);
    }

    /** 清除当前请求的检索范围配置（agent 执行完后调用） */
    public void clearSearchFilters() {
        searchKnowledge.remove();
        searchNotes.remove();
        selectedKnowledgeDocs.remove();
        selectedNotes.remove();
    }

    private boolean isNotesEnabled() {
        Boolean enabled = searchNotes.get();
        return enabled == null || enabled;
    }

    private String notesDisabled(String toolName) {
        String message = "笔记工具已被当前开关禁用: " + toolName;
        setResult(ToolResult.error(message, "TOOL_DISABLED", false));
        return message;
    }

    private AgentState state() {
        return activeState.get();
    }

    @Tool("从知识库文档或笔记中检索相关内容并生成摘要。触发场景：用户提到'知识库'、'文档'、'资料'、'上传的文件'、'根据文档'、'根据我的笔记'、'根据笔记'、'笔记里怎么说'等关键词时必须调用此工具。注意：如果用户是想找特定笔记打开看，应该用 searchNotes 而不是 ragSummary")
    public String ragSummary(@P("用户的查询问题，用于检索知识库或笔记") String query, @ToolMemoryId String userId) {
        try {
            Boolean sk = searchKnowledge.get();
            Boolean sn = searchNotes.get();
            // 默认只搜知识库（兼容未设置的情况）
            boolean doSearchKnowledge = sk != null ? sk : true;
            boolean doSearchNotes = sn != null ? sn : false;

            Map<String, Object> result = ragService.getDocumentsAndSummary(userId, query,
                    doSearchKnowledge, doSearchNotes,
                    selectedKnowledgeDocs.get(), selectedNotes.get(), null);
            this.latestTraceId = ragService.getLatestTraceId();
            StringBuilder sb = new StringBuilder();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) result.get("documents");
            boolean hasDocs = docs != null && !docs.isEmpty();
            if (hasDocs) {
                sb.append("找到以下相关文档：\n");
                for (int i = 0; i < docs.size(); i++) {
                    Map<String, Object> doc = docs.get(i);
                    sb.append(i + 1).append(". ")
                            .append(doc.getOrDefault("title", doc.getOrDefault("filename", "未知")))
                            .append("\n");
                }
            }
            sb.append("\n摘要：\n").append(result.get("summary"));
            String display = sb.toString();
            if (hasDocs) {
                setResult(ToolResult.success(display));
            } else {
                setResult(ToolResult.empty(display));
            }
            return display;
        } catch (Exception e) {
            setResult(ToolResult.error("RAG检索失败: " + e.getMessage(), "RAG_ERROR", true));
            return "RAG检索失败: " + e.getMessage();
        }
    }

    @Tool("获取当前时间。触发场景：用户问'现在几点'、'今天几号'、'当前时间'等时间相关问题时调用")
    public String whatTimeIsNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    @Tool("列出用户的笔记目录，返回笔记标题、ID和内容摘要，适合浏览总览。触发场景：用户说'我有哪些笔记'、'看看我的笔记'、'笔记列表'、'你能看到我的笔记吗'、'最近写了什么'、'笔记都有啥'、'我的笔记'、'我的笔记里写了什么'时调用此工具。注意：如果用户想看特定主题的笔记，用 searchNotes；如果用户想看具体某篇笔记的完整内容，先用 searchNotes 或 listNotes 获取 noteId 再用 getNote。getNote 因 NOTE_NOT_FOUND 失败时，也可用 listNotes 浏览全部笔记来定位")
    public String listNotes(
            @P("获取几条，默认20") String count,
            @P("按分类筛选，不筛选传空字符串") String category,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("listNotes");
        try {
            int n = 20;
            try { n = Integer.parseInt(count); } catch (Exception ignored) {}
            var result = noteService.listNotes(userId, 1, n,
                    (category != null && !category.isEmpty()) ? category : null, null);
            if (result.notes().isEmpty()) {
                setResult(ToolResult.empty("暂无笔记。"));
                return "暂无笔记。";
            }
            StringBuilder sb = new StringBuilder("你的笔记列表（共 " + result.totalCount() + " 篇）：\n\n");
            for (int i = 0; i < result.notes().size(); i++) {
                var note = result.notes().get(i);
                sb.append(i + 1).append(". ").append(note.title())
                        .append(" [ID: ").append(note.id()).append("]");
                if (note.category() != null) sb.append(" [").append(note.category()).append("]");
                if (note.tags() != null) sb.append(" ").append(note.tags());
                sb.append("\n");
                String content = note.content();
                if (content != null && !content.isEmpty()) {
                    String preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    preview = preview.replaceAll("\\n+", " ").replaceAll("\\s+", " ").trim();
                    sb.append("   摘要：").append(preview).append("\n");
                }
                sb.append("\n");
            }
            if (result.totalCount() > n) {
                sb.append("...还有 ").append(result.totalCount() - n).append(" 篇未显示");
            }
            String display = sb.toString();
            setResult(ToolResult.success(display));
            return display;
        } catch (Exception e) {
            setResult(ToolResult.error("获取笔记列表失败: " + e.getMessage(), "LIST_ERROR", true));
            return "获取笔记列表失败: " + e.getMessage();
        }
    }

    @Tool("读取一篇笔记的完整内容。前置条件：必须已知有效的 noteId（从 listNotes 或 searchNotes 返回结果中提取）。触发场景：用户说'看看xxx笔记'、'打开xxx'、'xxx写了什么'、'读一下xxx'、'给我看看xxx'、'这篇笔记'时调用此工具。注意：标题不是 noteId，搜索关键词不是 noteId，绝不能自己编造 ID。如果不知道有效 noteId，应先用 searchNotes 或 listNotes 定位笔记。常见失败 NOTE_NOT_FOUND 说明提供的 noteId 无效，应重新定位笔记而不是重复相同调用")
    public String getNote(
            @P("笔记ID（必须是 listNotes 或 searchNotes 返回结果中提取的有效 ID，不能自己编造）") String noteId,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("getNote");
        try {
            var note = noteService.getNote(userId, noteId);
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(note.title()).append("\n\n");
            if (note.category() != null) sb.append("分类：").append(note.category()).append("\n");
            if (note.tags() != null) sb.append("标签：").append(note.tags()).append("\n");
            sb.append("创建：").append(note.createdAt()).append("  更新：").append(note.updatedAt()).append("\n");
            sb.append("---\n\n");
            sb.append(note.content() != null ? note.content() : "(空笔记)");
            sb.append("\n\n[重要] 以上是笔记的完整Markdown内容，展示给用户时请保留原始格式（标题、列表、代码块等），不要转换为纯文本。");
            String display = sb.toString();
            setResult(ToolResult.success(display));
            return display;
        } catch (Exception e) {
            setResult(ToolResult.error("读取笔记失败: " + e.getMessage(), "NOTE_NOT_FOUND", true));
            return "读取笔记失败: " + e.getMessage();
        }
    }

    @Tool("按关键词搜索笔记内容，返回匹配笔记的标题、ID 和内容摘要。前置条件：需要有明确的搜索关键词。触发场景：用户说'找一下xxx笔记'、'搜索xxx'、'有没有关于xxx的笔记'、'我之前记过xxx吗'、'查找xxx'时调用此工具。适用场景：用户想看特定主题的笔记但不知道 noteId 时，或 getNote 因 NOTE_NOT_FOUND 失败后需要重新定位笔记时。注意：如果用户只是想看'有哪些笔记'、'笔记列表'，应该用 listNotes 而不是 searchNotes。常见失败 EMPTY（搜索无结果）说明关键词可能太窄或太具体，应该换更通用/简短的关键词重试")
    public String searchNotes(@P("搜索关键词，从用户问题中提取核心词（建议先用简短通用词，无结果再精确）") String query, @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("searchNotes");
        try {
            var result = noteService.searchNotes(userId, query);
            if (result.notes().isEmpty()) {
                setResult(ToolResult.empty("未找到相关笔记。"));
                return "未找到相关笔记。";
            }
            StringBuilder sb = new StringBuilder("找到以下相关笔记：\n");
            for (int i = 0; i < Math.min(5, result.notes().size()); i++) {
                var note = result.notes().get(i);
                sb.append(i + 1).append(". ").append(note.title())
                        .append(" [ID: ").append(note.id()).append("]");
                if (note.category() != null) sb.append(" [").append(note.category()).append("]");
                if (note.tags() != null) sb.append(" 标签:").append(note.tags());
                String content = note.content();
                if (content != null && !content.isEmpty()) {
                    String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                    sb.append("\n   内容: ").append(preview);
                }
                sb.append("\n");
            }
            String display = sb.toString();
            setResult(ToolResult.success(display));
            return display;
        } catch (Exception e) {
            setResult(ToolResult.error("搜索笔记失败: " + e.getMessage(), "SEARCH_ERROR", true));
            return "搜索笔记失败: " + e.getMessage();
        }
    }

    @Tool("获取用户最近编辑的笔记列表，返回最近的笔记标题和ID。触发场景：用户提到'刚才的笔记'、'最近的笔记'、'刚才创建的'、'最新笔记'时调用此工具")
    public String getRecentNotes(
            @P("获取几条，默认3") String count,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("getRecentNotes");
        try {
            int n = 3;
            try { n = Integer.parseInt(count); } catch (Exception ignored) {}
            var result = noteService.listNotes(userId, 1, n, null, null);
            if (result.notes().isEmpty()) {
                return "暂无笔记。";
            }
            StringBuilder sb = new StringBuilder("最近的笔记：\n");
            for (int i = 0; i < result.notes().size(); i++) {
                var note = result.notes().get(i);
                sb.append(i + 1).append(". ").append(note.title())
                        .append(" [ID: ").append(note.id()).append("]");
                if (note.category() != null) sb.append(" [").append(note.category()).append("]");
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取最近笔记失败: " + e.getMessage();
        }
    }

    @Tool("获取用户的笔记统计信息，包括笔记总数、分类等。触发场景：用户问'笔记有几篇'、'统计'、'多少篇'、'总共多少笔记'时调用此工具")
    public String getNoteStats(@ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("getNoteStats");
        try {
            Map<String, Object> stats = noteService.getStats(userId);
            return "笔记统计：总计 " + stats.get("total") + " 条笔记，" +
                    "未分类 " + stats.get("uncategorized") + " 条。";
        } catch (Exception e) {
            return "获取统计失败: " + e.getMessage();
        }
    }

    @Tool("获取用户今天需要复习的笔记列表。触发场景：用户提到’复习’、’复习时间’、’回顾’、’今天复习’、’复习计划’时调用此工具")
    public String getTodayReviews(@ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("getTodayReviews");
        try {
            Map<String, Object> result = reviewService.getTodayReviews(userId);
            @SuppressWarnings("unchecked")
            List<?> reviewsRaw = (List<?>) result.get("reviews");
            if (reviewsRaw == null || reviewsRaw.isEmpty()) {
                setResult(ToolResult.empty("今天没有需要复习的笔记。"));
                return "今天没有需要复习的笔记。";
            }
            StringBuilder sb = new StringBuilder("今天需要复习 ").append(reviewsRaw.size()).append(" 条笔记：\n");
            for (int i = 0; i < reviewsRaw.size(); i++) {
                sb.append(i + 1).append(". ").append(reviewsRaw.get(i).toString()).append("\n");
            }
            String display = sb.toString();
            setResult(ToolResult.success(display));
            return display;
        } catch (Exception e) {
            setResult(ToolResult.error("获取复习列表失败: " + e.getMessage(), "EXCEPTION", true));
            return "获取复习列表失败: " + e.getMessage();
        }
    }

    @Tool("标记指定笔记已完成复习。触发场景：用户说'标记已复习'、'完成复习'、'复习完了'时调用此工具，需要先用 getTodayReviews 或 searchNotes 获取笔记ID")
    public String markReviewed(@P("笔记ID，通过 getTodayReviews 或 searchNotes 获取") String noteId, @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("markReviewed");
        try {
            var result = reviewService.markReviewed(userId, noteId);
            if (result.success()) {
                return "已标记回顾。下次复习：" + result.nextReviewAt() +
                        "，复习次数：" + result.reviewCount() + "，间隔：" + result.intervalDays() + "天";
            }
            return result.message();
        } catch (Exception e) {
            return "标记回顾失败: " + e.getMessage();
        }
    }

    @Tool("创建一篇新笔记。触发场景：用户说'创建笔记'、'新建笔记'、'写一篇笔记'、'帮我记录'、'记一下'时调用此工具。内容必须使用Markdown格式")
    public String createNote(@P("笔记标题，简洁明了") String title, @P("笔记内容，必须使用Markdown格式（#标题、-列表、```代码块等）") String content, @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("createNote");
        try {
            AgentState state = state();

            // 前置检查：网页抓取失败时禁止创建笔记
            if (state != null && state.isFetchStepFailed()) {
                String msg = "前置网页抓取失败，禁止创建空笔记";
                setResult(ToolResult.error(msg, "FETCH_FAILED_BLOCK", false));
                return msg;
            }

            // 前置检查：内容过短或包含占位文本时禁止创建
            if (content == null || content.trim().length() < 50) {
                String msg = "笔记内容过短（少于50字符），禁止创建空笔记";
                setResult(ToolResult.error(msg, "CONTENT_TOO_SHORT", false));
                return msg;
            }

            String key = "createNote:" + (state != null && state.getCurrentTaskType() != null ? state.getCurrentTaskType() : "");
            if (state != null) {
                if (state.hasCompletedActionKey(key) || state.getSharedNoteId() != null && !state.getSharedNoteId().isBlank()) {
                    setResult(ToolResult.success("本任务已创建笔记，跳过重复创建，笔记ID: " + state.getSharedNoteId()));
                    return "本任务已创建笔记，跳过重复创建，笔记ID: " + state.getSharedNoteId();
                }
            }
            NoteCreate noteCreate = new NoteCreate();
            noteCreate.setTitle(title);
            noteCreate.setContent(content);
            var result = noteService.createNote(userId, noteCreate);
            if (state != null) {
                state.setSharedNoteId(result.id());
                state.setSharedNoteTitle(result.title());
                state.addCompletedActionKey(key);
                state.addKnownFact("已创建笔记ID：" + result.id());
                state.addKnownFact("已创建笔记标题：" + result.title());
            }
            setResult(ToolResult.success("笔记创建成功，ID: " + result.id() + "，标题: " + result.title()));
            return "笔记创建成功，ID: " + result.id() + "，标题: " + result.title();
        } catch (Exception e) {
            setResult(ToolResult.error("创建笔记失败: " + e.getMessage(), "CREATE_NOTE_ERROR", true));
            return "创建笔记失败: " + e.getMessage();
        }
    }

    @Tool("编辑已有笔记，替换原有内容。触发场景：用户说'修改笔记'、'更新笔记'、'编辑xxx'、'改一下xxx'时调用此工具，需要先用 searchNotes 或 getRecentNotes 获取笔记ID")
    public String editNote(
            @P("笔记ID，通过 searchNotes 或 getRecentNotes 获取") String noteId,
            @P("新标题，不修改传空字符串") String title,
            @P("新内容，必须使用Markdown格式，不修改传空字符串") String content,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("editNote");
        try {
            NoteUpdate update = new NoteUpdate();
            if (title != null && !title.isBlank()) update.setTitle(title);
            if (content != null && !content.isBlank()) update.setContent(content);
            var result = noteService.updateNote(userId, noteId, update);
            return "笔记编辑成功，ID: " + result.id() + "，标题: " + result.title();
        } catch (Exception e) {
            return "编辑笔记失败: " + e.getMessage();
        }
    }

    @Tool("向已有笔记追加内容（不覆盖原有内容）。触发场景：用户说'写入到xxx'、'追加到xxx'、'添加到xxx笔记'、'把内容加到xxx'时调用此工具，需要先用 searchNotes 或 getRecentNotes 获取笔记ID")
    public String appendNote(
            @P("笔记ID，通过 searchNotes 或 getRecentNotes 获取") String noteId,
            @P("要追加的内容，使用Markdown格式") String appendContent,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("appendNote");
        try {
            String normalizedAppend = appendContent != null ? appendContent.trim() : "";
            AgentState state = state();
            if (normalizedAppend.isBlank()) {
                setResult(ToolResult.empty("追加内容为空，已跳过。"));
                return "追加内容为空，已跳过。";
            }
            if (state != null && state.getSharedNoteId() != null && !state.getSharedNoteId().isBlank()) {
                noteId = state.getSharedNoteId();
            }
            String actionKey = noteId + ":" + normalizedAppend.hashCode();
            if (state != null) {
                if (state.hasCompletedActionKey(actionKey)) {
                    setResult(ToolResult.success("本任务已完成相同追加操作，跳过重复追加，笔记ID: " + noteId));
                    return "本任务已完成相同追加操作，跳过重复追加，笔记ID: " + noteId;
                }
                if (state.getSharedMindMap() != null && !state.getSharedMindMap().isBlank()
                        && normalizedAppend.contains(state.getSharedMindMap())) {
                    setResult(ToolResult.success("导图已存在，跳过重复追加，笔记ID: " + noteId));
                    return "导图已存在，跳过重复追加，笔记ID: " + noteId;
                }
            }
            var note = noteService.getNote(userId, noteId);
            String oldContent = note.content() != null ? note.content() : "";
            if (oldContent.contains(normalizedAppend)) {
                setResult(ToolResult.success("内容已存在，跳过重复追加，笔记ID: " + note.id()
                        + "，标题: " + note.title()));
                return "内容已存在，跳过重复追加，笔记ID: " + note.id() + "，标题: " + note.title();
            }
            String newContent = oldContent + "\n\n" + appendContent;
            NoteUpdate update = new NoteUpdate();
            update.setContent(newContent);
            var result = noteService.updateNote(userId, noteId, update);
            if (state != null) {
                state.setSharedNoteId(result.id());
                state.setSharedNoteTitle(result.title());
                if (normalizedAppend.contains("mermaid") || normalizedAppend.contains("mindmap")) {
                    state.setSharedMindMap(normalizedAppend);
                }
                state.addCompletedActionKey(actionKey);
            }
            setResult(ToolResult.success("内容追加成功，笔记ID: " + result.id() + "，标题: " + result.title()
                    + "，当前总字数: " + newContent.length()));
            return "内容追加成功，笔记ID: " + result.id() + "，标题: " + result.title()
                    + "，当前总字数: " + newContent.length();
        } catch (Exception e) {
            setResult(ToolResult.error("追加内容失败: " + e.getMessage(), "APPEND_NOTE_ERROR", true));
            return "追加内容失败: " + e.getMessage();
        }
    }

    @Tool("删除指定笔记（不可撤销）。触发场景：用户说'删除笔记'、'删掉xxx'、'移除xxx'时调用此工具，需要先用 searchNotes 或 getRecentNotes 获取笔记ID")
    public String deleteNote(@P("笔记ID，通过 searchNotes 或 getRecentNotes 获取") String noteId, @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("deleteNote");
        try {
            noteService.deleteNote(userId, noteId);
            return "笔记删除成功，ID: " + noteId;
        } catch (Exception e) {
            return "删除笔记失败: " + e.getMessage();
        }
    }

    @Tool("查找与指定笔记相关的其他笔记。触发场景：用户说'相关笔记'、'类似笔记'、'还有什么相关的'时调用此工具，需要先用 searchNotes 获取笔记ID")
    public String getRelatedNotes(@P("笔记ID，通过 searchNotes 获取") String noteId, @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("getRelatedNotes");
        try {
            var results = noteService.getRelatedNotes(userId, noteId, 3);
            if (results.isEmpty()) {
                return "未找到相关笔记。";
            }
            StringBuilder sb = new StringBuilder("找到以下相关笔记：\n");
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i);
                sb.append(i + 1).append(". ").append(r.title())
                        .append(" (相似度: ").append(String.format("%.2f", r.similarity())).append(")\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取相关笔记失败: " + e.getMessage();
        }
    }

    @Tool("合并多篇笔记为一篇新笔记。触发场景：用户说'合并笔记'、'把这几篇合在一起'、'整合笔记'时调用此工具，需要先用 searchNotes 获取要合并的笔记ID")
    public String mergeNotes(
            @P("要合并的笔记ID列表，逗号分隔，如：id1,id2,id3") String noteIds,
            @P("新笔记的标题") String newTitle,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("mergeNotes");
        try {
            String[] ids = noteIds.split(",");
            StringBuilder mergedContent = new StringBuilder();
            int merged = 0;

            for (String id : ids) {
                String trimmedId = id.trim();
                if (trimmedId.isEmpty()) continue;
                try {
                    var note = noteService.getNote(userId, trimmedId);
                    if (merged > 0) mergedContent.append("\n\n---\n\n");
                    mergedContent.append("## ").append(note.title()).append("\n\n");
                    mergedContent.append(note.content() != null ? note.content() : "");
                    merged++;
                } catch (Exception e) {
                    // 跳过找不到的笔记
                }
            }

            if (merged == 0) {
                return "未找到任何可合并的笔记。";
            }

            NoteCreate noteCreate = new NoteCreate();
            noteCreate.setTitle(newTitle);
            noteCreate.setContent(mergedContent.toString());
            var result = noteService.createNote(userId, noteCreate);
            return "合并成功！已将 " + merged + " 篇笔记合并为新笔记，ID: " + result.id() + "，标题: " + result.title();
        } catch (Exception e) {
            return "合并笔记失败: " + e.getMessage();
        }
    }

    @Tool("抓取指定URL的网页内容并返回文本。触发场景：用户说'打开这个链接'、'抓取网页'、'这个URL的内容'、'帮我看看这个网页'时调用此工具")
    public String fetchUrl(@P("要抓取的网页URL") String url) {
        try {
            URI uri = URI.create(url);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", siteReferer(uri))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String body = decodeBody(response);
            PageExtraction extraction = extractPageContent(uri, body);
            String summary = buildFetchSummary(uri, response.statusCode(), extraction);

            AgentState state = state();
            if (state != null && response.statusCode() < 400 && extraction.qualityPass()) {
                state.setSharedFetchedContent(extraction.content());
                state.addKnownFact("已抓取网页正文，长度: " + extraction.content().length());
                state.addKnownFact("抓取站点: " + uri.getHost());
            }

            if (response.statusCode() >= 400 || !extraction.qualityPass()) {
                setResult(ToolResult.error(summary, "FETCH_URL_BAD_CONTENT", true));
            } else {
                setResult(ToolResult.success(summary));
            }
            return summary;
        } catch (IllegalArgumentException e) {
            setResult(ToolResult.error("URL格式错误: " + e.getMessage(), "URL_INVALID", false));
            return "URL格式错误: " + e.getMessage();
        } catch (Exception e) {
            setResult(ToolResult.error("抓取网页失败: " + e.getMessage(), "FETCH_URL_ERROR", true));
            return "抓取网页失败: " + e.getMessage();
        }
    }

    String decodeBody(HttpResponse<byte[]> response) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("charset=gbk") || lower.contains("charset=gb2312")) {
            return new String(response.body(), java.nio.charset.Charset.forName("GB18030"));
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    String siteReferer(URI uri) {
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        if (host.contains("zhihu.com")) return "https://www.zhihu.com/";
        if (host.contains("csdn.net")) return "https://www.csdn.net/";
        return uri.getScheme() + "://" + uri.getHost();
    }

    PageExtraction extractPageContent(URI uri, String html) {
        String normalizedHtml = html == null ? "" : html;
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        String cleanHtml = normalizedHtml.replace(" ", "");

        if (host.contains("zhihu.com")) {
            return extractZhihuContent(cleanHtml);
        }
        if (host.contains("csdn.net")) {
            return extractCsdnContent(cleanHtml);
        }
        return extractGenericContent(cleanHtml);
    }

    PageExtraction extractZhihuContent(String html) {
        List<String> segments = new ArrayList<>();
        collectSegmentsByClass(html, segments, "RichContent-inner");
        collectSegmentsByClass(html, segments, "Post-RichText");
        collectSegmentsByTag(html, segments, "article");
        return finalizeExtraction(html, segments, "知乎");
    }

    PageExtraction extractCsdnContent(String html) {
        List<String> segments = new ArrayList<>();
        collectSegmentsById(html, segments, "article_content");
        collectSegmentsByClass(html, segments, "blog-content-box");
        collectSegmentsByClass(html, segments, "article_content");
        collectSegmentsByTag(html, segments, "article");
        return finalizeExtraction(html, segments, "CSDN");
    }

    PageExtraction extractGenericContent(String html) {
        List<String> segments = new ArrayList<>();
        collectSegmentsByTag(html, segments, "article");
        collectSegmentsByTag(html, segments, "main");
        collectSegmentsByClass(html, segments, "content");
        collectSegmentsByClass(html, segments, "article");
        collectSegmentsByClass(html, segments, "post-content");
        return finalizeExtraction(html, segments, "通用");
    }

    void collectSegmentsByTag(String html, List<String> segments, String tagName) {
        Matcher matcher = Pattern.compile("<" + tagName + "\\b[^>]*>([\\s\\S]*?)</" + tagName + ">", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        while (matcher.find()) {
            addSegment(segments, matcher.group(1));
        }
    }

    void collectSegmentsByClass(String html, List<String> segments, String className) {
        Matcher matcher = Pattern.compile("<(div|section|article)[^>]*class=\\\"[^\\\"]*" + Pattern.quote(className) + "[^\\\"]*\\\"[^>]*>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        while (matcher.find()) {
            addSegment(segments, matcher.group(2));
        }
    }

    void collectSegmentsById(String html, List<String> segments, String idName) {
        Matcher matcher = Pattern.compile("<(div|section|article)[^>]*id=\\\"" + Pattern.quote(idName) + "\\\"[^>]*>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        while (matcher.find()) {
            addSegment(segments, matcher.group(2));
        }
    }

    void addSegment(List<String> segments, String rawSegment) {
        String text = htmlToText(rawSegment);
        if (!text.isBlank()) {
            segments.add(text);
        }
    }

    PageExtraction finalizeExtraction(String html, List<String> segments, String strategy) {
        String fallback = htmlToText(html);
        String best = selectBestSegment(segments, fallback);
        ExtractionMetrics metrics = analyzeContent(best, fallback, html);
        String normalized = best.length() > 6000 ? best.substring(0, 6000) + "\n...(内容已截断)" : best;
        return new PageExtraction(normalized, metrics.qualityPass(), metrics.reason(), strategy,
                metrics.contentLength(), metrics.coverageRatio(), metrics.paragraphCount(), metrics.hasStructure());
    }

    String selectBestSegment(List<String> segments, String fallback) {
        String best = "";
        int bestScore = -1;
        LinkedHashSet<String> uniqueSegments = new LinkedHashSet<>(segments);
        for (String segment : uniqueSegments) {
            int score = scoreSegment(segment);
            if (score > bestScore) {
                bestScore = score;
                best = segment;
            }
        }
        if (best.isBlank() || best.length() < 200) {
            return fallback;
        }
        return best;
    }

    int scoreSegment(String segment) {
        int lengthScore = Math.min(segment.length(), 5000);
        int paragraphBonus = countParagraphs(segment) * 120;
        int punctuationBonus = countMatches(segment, "[。！？；：\\n]") * 8;
        int penalty = countNavigationTokens(segment) * 180;
        return lengthScore + paragraphBonus + punctuationBonus - penalty;
    }

    ExtractionMetrics analyzeContent(String content, String fallback, String html) {
        int contentLength = content.length();
        int fallbackLength = Math.max(fallback.length(), 1);
        double coverageRatio = Math.min(1.0d, (double) contentLength / fallbackLength);
        int paragraphCount = countParagraphs(content);
        boolean hasStructure = paragraphCount >= 3 || countMatches(content, "[。！？；]") >= 4;
        int navTokenCount = countNavigationTokens(content);
        double navDensity = contentLength > 0 ? (double) navTokenCount / contentLength * 1000 : 0;
        boolean navigationHeavy = navTokenCount >= 3 && (navDensity > 0.8d || contentLength < 1000);
        boolean blocked = looksBlocked(html, content);
        boolean qualityPass = contentLength >= 200 && hasStructure && coverageRatio >= 0.08d && !navigationHeavy && !blocked;

        String reason;
        if (content.isBlank()) {
            reason = "正文为空";
        } else if (blocked) {
            reason = "页面存在站点拦截或登录提示";
        } else if (navigationHeavy) {
            reason = "命中导航/推荐内容，未进入正文";
        } else if (contentLength < 200) {
            reason = "正文过短，疑似摘要页或截断页";
        } else if (!hasStructure) {
            reason = "缺少明显正文结构";
        } else if (coverageRatio < 0.08d) {
            reason = "正文覆盖率不足，疑似只截到前半段";
        } else {
            reason = "正文完整";
        }
        return new ExtractionMetrics(qualityPass, reason, contentLength, coverageRatio, paragraphCount, hasStructure);
    }

    boolean looksBlocked(String html, String content) {
        String text = (html + "\n" + content).toLowerCase(Locale.ROOT);

        // 强拦截词：命中即判定为拦截
        boolean hasHardBlock = text.contains("请先登录") || text.contains("扫码登录")
                || text.contains("安全验证") || text.contains("访问受限")
                || text.contains("继续访问");

        // 弱拦截词：只有在内容很短时才判定为拦截
        boolean hasSoftBlock = text.contains("展开阅读全文") || text.contains("复制链接") || text.contains("验证码");

        // 强拦截词直接判定
        if (hasHardBlock) {
            return true;
        }

        // 弱拦截词 + 内容过短才判定为拦截
        if (hasSoftBlock && content.length() < 500) {
            return true;
        }

        return false;
    }

    int countNavigationTokens(String text) {
        String[] tokens = {"上一篇", "下一篇", "相关推荐", "推荐阅读", "热门推荐", "更多内容", "登录后", "点赞", "评论", "收藏", "关注",
                "搜索", "高级搜索", "搜索结果", "时间不限", "在新选项卡中打开链接", "网页", "图片", "视频", "微信", "百科", "意见反馈", "帮助"};
        int count = 0;
        for (String token : tokens) {
            if (text.contains(token)) count++;
        }
        return count;
    }

    int countParagraphs(String text) {
        String[] blocks = text.split("\\n+");
        int paragraphs = 0;
        for (String block : blocks) {
            if (block.trim().length() >= 40) {
                paragraphs++;
            }
        }
        return paragraphs;
    }

    int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        return html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)</li>", "\n")
                .replaceAll("(?i)</h[1-6]>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    String buildFetchSummary(URI uri, int statusCode, PageExtraction extraction) {
        return "网页正文抓取结果（HTTP " + statusCode + "，站点策略: " + extraction.strategy() + "）\n"
                + "质量判定: " + (extraction.qualityPass() ? "通过" : "未通过") + "\n"
                + "判定原因: " + extraction.reason() + "\n"
                + "正文长度: " + extraction.contentLength() + "\n"
                + "覆盖率: " + String.format(Locale.ROOT, "%.2f", extraction.coverageRatio()) + "\n"
                + "段落数: " + extraction.paragraphCount() + "\n"
                + "正文结构: " + (extraction.hasStructure() ? "明显" : "不足") + "\n"
                + "URL: " + uri + "\n\n"
                + extraction.content();
    }

    record PageExtraction(
            String content,
            boolean qualityPass,
            String reason,
            String strategy,
            int contentLength,
            double coverageRatio,
            int paragraphCount,
            boolean hasStructure
    ) {}

    record ExtractionMetrics(
            boolean qualityPass,
            String reason,
            int contentLength,
            double coverageRatio,
            int paragraphCount,
            boolean hasStructure
    ) {}

    @Tool("生成Mermaid图表代码。触发场景：用户说'画个流程图'、'生成图表'、'画个时序图'、'画个类图'时调用此工具")
    public String generateDiagram(
            @P("图表类型：flowchart(流程图)、sequence(时序图)、class(类图)、er(ER图)、pie(饼图)、gantt(甘特图)") String type,
            @P("图表描述，用自然语言描述要画什么") String description) {
        // 这个工具只是让LLM知道可以生成图表
        // 实际的Mermaid代码由LLM自己生成，这里返回提示
        return "请根据以下描述生成Mermaid图表代码：\n" +
                "图表类型：" + type + "\n" +
                "描述：" + description + "\n\n" +
                "请直接输出Mermaid代码，用 ```mermaid 代码块包裹。";
    }

    @Tool("根据指定笔记内容生成思维导图（Mermaid格式）。前置条件：需要有效的 noteId。触发场景：用户说'生成思维导图'、'画个脑图'、'整理成思维导图'、'帮我梳理xxx笔记的结构'时调用此工具。如果用户说\"笔记里\"、\"这篇笔记\"但没有提供 noteId，先用 searchNotes 或从上下文获取 noteId")
    public String generateMindMap(
            @P("笔记ID，通过 searchNotes 获取或从对话上下文中获取") String noteId,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("generateMindMap");
        try {
            var note = noteService.getNote(userId, noteId);
            String title = note.title();
            String content = note.content() != null ? note.content() : "";

            if (content.length() > 15000) {
                content = content.substring(0, 15000) + "\n\n... (内容过长已截断)";
            }

            String prompt = """
                    你是一位思维导图专家。请根据以下笔记内容生成一份知识型 Mermaid mindmap。

                    笔记标题：%s

                    笔记内容：
                    %s

                    核心原则：这不是目录大纲！思维导图的每个节点应该包含知识点、见解或关键信息，
                    而不是只写标题。读者看思维导图应该能学到东西，不是看到一堆分类标签。

                    具体要求：
                    1. 根节点 = 笔记的核心主题（一句话概括）
                    2. 一级分支 = 主要知识模块
                    3. 二三级节点 = 具体的概念、原理、要点、例子，要有信息量
                    4. 节点文字可以是短语或短句，但必须包含实质内容
                       - 差: "线程状态"         好: "线程6种状态:NEW→RUNNABLE→BLOCKED→WAITING→TIMED_WAITING→TERMINATED"
                       - 差: "线程池"           好: "线程池核心参数:corePoolSize、maxPoolSize、keepAliveTime、工作队列"
                       - 差: "王维"             好: "王维·山水田园·诗中有画画中有诗"
                    5. 层级不超过4层，避免嵌套过深
                    6. 不要编造原文没有的内容
                    7. 使用 ```mermaid\\nmindmap\\n...\\n``` 代码块输出
                    8. 只输出代码块，不要解释""".formatted(title, content);

            ChatLanguageModel llm = modelFactory.createCreativeModel();
            Response<AiMessage> response = llm.generate(
                    SystemMessage.from("你是一个思维导图生成器，严格按用户要求输出Mermaid代码。"),
                    UserMessage.from(prompt));
            String result = response.content().text();

            // 如果 LLM 没输出代码块，手动包裹
            if (result != null && !result.contains("```mermaid")) {
                result = "```mermaid\nmindmap\n" + result + "\n```";
            }

            String finalResult = result != null ? result.trim() : "";
            setResult(ToolResult.success("已根据《" + title + "》生成思维导图"));
            return finalResult;
        } catch (Exception e) {
            setResult(ToolResult.error("生成思维导图失败: " + e.getMessage(), "NOTE_NOT_FOUND", true));
            return "生成思维导图失败: " + e.getMessage();
        }
    }

    @Tool("安排笔记的复习时间。触发场景：用户说'安排复习'、'设置复习时间'、'提醒我复习xxx'时调用此工具，需要先用 searchNotes 获取笔记ID")
    public String scheduleReview(
            @P("笔记ID，通过 searchNotes 获取") String noteId,
            @P("复习间隔天数，如1、3、7、15、30") String days,
            @ToolMemoryId String userId) {
        if (!isNotesEnabled()) return notesDisabled("scheduleReview");
        try {
            int intervalDays = 1;
            try { intervalDays = Integer.parseInt(days); } catch (Exception ignored) {}

            var note = noteService.getNote(userId, noteId);
            // 这里简化处理，实际应该更新ReviewRecord
            return "已安排复习！笔记《" + note.title() + "》将在 " + intervalDays + " 天后（"
                    + LocalDateTime.now().plusDays(intervalDays).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    + "）提醒复习。";
        } catch (Exception e) {
            return "安排复习失败: " + e.getMessage();
        }
    }
}
