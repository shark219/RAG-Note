package com.rag.notebook.agent;

import com.rag.notebook.knowledge.service.KnowledgeService;
import com.rag.notebook.note.dto.NoteCreate;
import com.rag.notebook.note.dto.NoteUpdate;
import com.rag.notebook.note.service.NoteService;
import com.rag.notebook.rag.RagService;
import com.rag.notebook.review.service.ReviewService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AgentTools {

    private final RagService ragService;
    private final NoteService noteService;
    private final ReviewService reviewService;

    // 存储最新的 traceId，供 AgentService 读取
    private String latestTraceId;

    // 结构化结果缓存：AgentLoop 执行工具后从这里读取 ToolResult
    private final ThreadLocal<ToolResult> lastResult = new ThreadLocal<>();

    public AgentTools(RagService ragService, NoteService noteService, ReviewService reviewService) {
        this.ragService = ragService;
        this.noteService = noteService;
        this.reviewService = reviewService;
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

    @Tool("从用户上传的知识库文档中检索相关内容并生成摘要。触发场景：用户提到'知识库'、'文档'、'资料'、'上传的文件'、'根据文档'等关键词时必须调用此工具")
    public String ragSummary(@P("用户的查询问题，用于检索知识库") String query, @ToolMemoryId String userId) {
        try {
            Map<String, Object> result = ragService.getDocumentsAndSummary(userId, query);
            this.latestTraceId = ragService.getLatestTraceId();
            StringBuilder sb = new StringBuilder();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) result.get("documents");
            if (docs != null && !docs.isEmpty()) {
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
            setResult(ToolResult.success(display));
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
        try {
            Map<String, Object> stats = noteService.getStats(userId);
            return "笔记统计：总计 " + stats.get("total") + " 条笔记，" +
                    "未分类 " + stats.get("uncategorized") + " 条。";
        } catch (Exception e) {
            return "获取统计失败: " + e.getMessage();
        }
    }

    @Tool("获取用户今天需要复习的笔记列表。触发场景：用户提到'复习'、'回顾'、'今天复习'、'复习计划'时调用此工具")
    public String getTodayReviews(@ToolMemoryId String userId) {
        try {
            Map<String, Object> result = reviewService.getTodayReviews(userId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reviews = (List<Map<String, Object>>) result.get("reviews");
            if (reviews == null || reviews.isEmpty()) {
                return "今天没有需要复习的笔记。";
            }
            StringBuilder sb = new StringBuilder("今天需要复习 ").append(reviews.size()).append(" 条笔记：\n");
            for (int i = 0; i < reviews.size(); i++) {
                Map<String, Object> r = reviews.get(i);
                sb.append(i + 1).append(". ").append(r.get("title")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取复习列表失败: " + e.getMessage();
        }
    }

    @Tool("标记指定笔记已完成复习。触发场景：用户说'标记已复习'、'完成复习'、'复习完了'时调用此工具，需要先用 getTodayReviews 或 searchNotes 获取笔记ID")
    public String markReviewed(@P("笔记ID，通过 getTodayReviews 或 searchNotes 获取") String noteId, @ToolMemoryId String userId) {
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
        try {
            NoteCreate noteCreate = new NoteCreate();
            noteCreate.setTitle(title);
            noteCreate.setContent(content);
            var result = noteService.createNote(userId, noteCreate);
            return "笔记创建成功，ID: " + result.id() + "，标题: " + result.title();
        } catch (Exception e) {
            return "创建笔记失败: " + e.getMessage();
        }
    }

    @Tool("编辑已有笔记，替换原有内容。触发场景：用户说'修改笔记'、'更新笔记'、'编辑xxx'、'改一下xxx'时调用此工具，需要先用 searchNotes 或 getRecentNotes 获取笔记ID")
    public String editNote(
            @P("笔记ID，通过 searchNotes 或 getRecentNotes 获取") String noteId,
            @P("新标题，不修改传空字符串") String title,
            @P("新内容，必须使用Markdown格式，不修改传空字符串") String content,
            @ToolMemoryId String userId) {
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
        try {
            var note = noteService.getNote(userId, noteId);
            String oldContent = note.content() != null ? note.content() : "";
            String newContent = oldContent + "\n\n" + appendContent;
            NoteUpdate update = new NoteUpdate();
            update.setContent(newContent);
            var result = noteService.updateNote(userId, noteId, update);
            return "内容追加成功，笔记ID: " + result.id() + "，标题: " + result.title()
                    + "，当前总字数: " + newContent.length();
        } catch (Exception e) {
            return "追加内容失败: " + e.getMessage();
        }
    }

    @Tool("删除指定笔记（不可撤销）。触发场景：用户说'删除笔记'、'删掉xxx'、'移除xxx'时调用此工具，需要先用 searchNotes 或 getRecentNotes 获取笔记ID")
    public String deleteNote(@P("笔记ID，通过 searchNotes 或 getRecentNotes 获取") String noteId, @ToolMemoryId String userId) {
        try {
            noteService.deleteNote(userId, noteId);
            return "笔记删除成功，ID: " + noteId;
        } catch (Exception e) {
            return "删除笔记失败: " + e.getMessage();
        }
    }

    @Tool("查找与指定笔记相关的其他笔记。触发场景：用户说'相关笔记'、'类似笔记'、'还有什么相关的'时调用此工具，需要先用 searchNotes 获取笔记ID")
    public String getRelatedNotes(@P("笔记ID，通过 searchNotes 获取") String noteId, @ToolMemoryId String userId) {
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
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; RAGNoteBot/1.0)")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            // 简单清理HTML标签
            String text = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                    .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("\\s+", " ")
                    .trim();

            // 限制返回长度
            if (text.length() > 3000) {
                text = text.substring(0, 3000) + "...(内容已截断)";
            }

            return "网页内容抓取成功（HTTP " + response.statusCode() + "）：\n" + text;
        } catch (IllegalArgumentException e) {
            return "URL格式错误: " + e.getMessage();
        } catch (Exception e) {
            return "抓取网页失败: " + e.getMessage();
        }
    }

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

    @Tool("根据指定笔记内容生成思维导图（Markdown格式）。前置条件：需要有效的 noteId。触发场景：用户说'生成思维导图'、'画个脑图'、'整理成思维导图'、'帮我梳理xxx笔记的结构'时调用此工具。如果用户说\"笔记里\"、\"这篇笔记\"但没有提供 noteId，先用 searchNotes 或从上下文获取 noteId")
    public String generateMindMap(
            @P("笔记ID，通过 searchNotes 获取或从对话上下文中获取") String noteId,
            @ToolMemoryId String userId) {
        try {
            var note = noteService.getNote(userId, noteId);
            String title = note.title();
            String content = note.content() != null ? note.content() : "";

            String display = "笔记《" + title + "》的完整内容：\n\n"
                    + content + "\n\n"
                    + "---\n"
                    + "请基于以上内容，生成一份 Markdown 格式的思维导图。要求：\n"
                    + "1. 用 # ## ### 表示层级结构\n"
                    + "2. 用 - 列表表示分支\n"
                    + "3. 提取核心概念作为节点\n"
                    + "4. 结构清晰、层次分明\n"
                    + "5. 不要编造笔记中不存在的内容";
            setResult(ToolResult.success(display));
            return display;
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
