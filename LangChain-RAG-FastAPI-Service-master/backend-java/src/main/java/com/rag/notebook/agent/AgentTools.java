package com.rag.notebook.agent;

import com.rag.notebook.knowledge.service.KnowledgeService;
import com.rag.notebook.note.dto.NoteCreate;
import com.rag.notebook.note.service.NoteService;
import com.rag.notebook.rag.RagService;
import com.rag.notebook.review.service.ReviewService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class AgentTools {

    private final RagService ragService;
    private final NoteService noteService;
    private final ReviewService reviewService;

    // 存储最新的 traceId，供 AgentService 读取
    private String latestTraceId;

    public AgentTools(RagService ragService, NoteService noteService, ReviewService reviewService) {
        this.ragService = ragService;
        this.noteService = noteService;
        this.reviewService = reviewService;
    }

    public String getLatestTraceId() {
        return latestTraceId;
    }

    @Tool("从用户上传的知识库文档中检索相关内容并生成摘要，适用于用户提到知识库、文档、资料等场景")
    public String ragSummary(@P("用户的查询问题") String query, @ToolMemoryId String userId) {
        try {
            Map<String, Object> result = ragService.getDocumentsAndSummary(userId, query);
            // 捕获 traceId
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
            return sb.toString();
        } catch (Exception e) {
            return "RAG检索失败: " + e.getMessage();
        }
    }

    @Tool("获取当前时间")
    public String whatTimeIsNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    @Tool("搜索用户自己的笔记，适用于用户要查找、搜索自己的笔记")
    public String searchNotes(@P("搜索关键词") String query, @ToolMemoryId String userId) {
        try {
            var result = noteService.searchNotes(userId, query);
            if (result.notes().isEmpty()) {
                return "未找到相关笔记。";
            }
            StringBuilder sb = new StringBuilder("找到以下相关笔记：\n");
            for (int i = 0; i < Math.min(5, result.notes().size()); i++) {
                var note = result.notes().get(i);
                sb.append(i + 1).append(". ").append(note.title());
                if (note.category() != null) sb.append(" [").append(note.category()).append("]");
                if (note.tags() != null) sb.append(" 标签:").append(note.tags());
                // 内容预览（最多 300 字）
                String content = note.content();
                if (content != null && !content.isEmpty()) {
                    String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                    sb.append("\n   内容: ").append(preview);
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索笔记失败: " + e.getMessage();
        }
    }

    @Tool("获取用户的笔记统计信息，包括笔记总数、分类等")
    public String getNoteStats(@ToolMemoryId String userId) {
        try {
            Map<String, Object> stats = noteService.getStats(userId);
            return "笔记统计：总计 " + stats.get("total") + " 条笔记，" +
                    "未分类 " + stats.get("uncategorized") + " 条。";
        } catch (Exception e) {
            return "获取统计失败: " + e.getMessage();
        }
    }

    @Tool("获取用户今天需要复习的笔记列表")
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

    @Tool("标记指定笔记已完成复习，noteId 为笔记ID")
    public String markReviewed(@P("笔记ID") String noteId, @ToolMemoryId String userId) {
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

    @Tool("创建一篇新笔记，需要提供标题和内容")
    public String createNote(@P("笔记标题") String title, @P("笔记内容") String content, @ToolMemoryId String userId) {
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

    @Tool("查找与指定笔记相关的其他笔记，noteId 为笔记ID")
    public String getRelatedNotes(@P("笔记ID") String noteId, @ToolMemoryId String userId) {
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
}
