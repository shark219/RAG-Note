package com.rag.notebook.agent;

import com.rag.notebook.knowledge.service.KnowledgeService;
import com.rag.notebook.note.dto.NoteCreate;
import com.rag.notebook.note.service.NoteService;
import com.rag.notebook.rag.RagService;
import com.rag.notebook.review.service.ReviewService;
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

    public AgentTools(RagService ragService, NoteService noteService, ReviewService reviewService) {
        this.ragService = ragService;
        this.noteService = noteService;
        this.reviewService = reviewService;
    }

    public String ragSummary(String query, String userId) {
        try {
            Map<String, Object> result = ragService.getDocumentsAndSummary(userId, query);
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

    public String whatTimeIsNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public String searchNotes(String query, int topK, String userId) {
        try {
            var result = noteService.searchNotes(userId, query);
            if (result.notes().isEmpty()) {
                return "未找到相关笔记。";
            }
            StringBuilder sb = new StringBuilder("找到以下相关笔记：\n");
            for (int i = 0; i < Math.min(topK, result.notes().size()); i++) {
                var note = result.notes().get(i);
                sb.append(i + 1).append(". ").append(note.title());
                if (note.category() != null) sb.append(" [").append(note.category()).append("]");
                if (note.tags() != null) sb.append(" 标签:").append(note.tags());
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索笔记失败: " + e.getMessage();
        }
    }

    public String getNoteStats(String userId) {
        try {
            Map<String, Object> stats = noteService.getStats(userId);
            return "笔记统计：总计 " + stats.get("total") + " 条笔记，" +
                    "未分类 " + stats.get("uncategorized") + " 条。";
        } catch (Exception e) {
            return "获取统计失败: " + e.getMessage();
        }
    }

    public String getTodayReviews(String userId) {
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

    public String markReviewed(String noteId, String userId) {
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

    public String createNote(String title, String content, String userId) {
        try {
            var result = noteService.createNote(userId, new NoteCreate() {{
                setTitle(title);
                setContent(content);
            }});
            return "笔记创建成功，ID: " + result.id() + "，标题: " + result.title();
        } catch (Exception e) {
            return "创建笔记失败: " + e.getMessage();
        }
    }

    public String getRelatedNotes(String noteId, int topK, String userId) {
        try {
            var results = noteService.getRelatedNotes(userId, noteId, topK);
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
