package com.rag.notebook.note.service;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.cache.CacheProtectionService;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.note.dto.*;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.repo.NoteRepository;
import com.rag.notebook.rag.VectorStoreService;
import com.rag.notebook.review.entity.ReviewRecord;
import com.rag.notebook.review.repo.ReviewRecordRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NoteService {

    private static final String CACHE_NOTE = "note:";
    private static final String CACHE_NOTE_LIST = "note:list:";
    private static final long TTL_NOTE = 1800L;      // 单条笔记 30 分钟
    private static final long TTL_NOTE_LIST = 300L;  // 列表 5 分钟

    private final NoteRepository noteRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final VectorStoreService vectorStoreService;
    private final ModelFactory modelFactory;
    private final CacheProtectionService cacheProtection;
    private final NoteService self;

    public NoteService(NoteRepository noteRepository,
                       ReviewRecordRepository reviewRecordRepository,
                       VectorStoreService vectorStoreService,
                       ModelFactory modelFactory,
                       CacheProtectionService cacheProtection,
                       @Lazy NoteService self) {
        this.noteRepository = noteRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.vectorStoreService = vectorStoreService;
        this.modelFactory = modelFactory;
        this.cacheProtection = cacheProtection;
        this.self = self;
    }

    @Transactional
    public NoteResponse createNote(String userId, NoteCreate request) {
        Note note = new Note();
        note.setId(UUID.randomUUID().toString().replace("-", ""));
        note.setUserId(userId);
        note.setTitle(request.getTitle());
        note.setContent(request.getContent());
        // 如果前端传了分类，直接使用；否则等 LLM 异步填充
        if (request.getCategory() != null && !request.getCategory().isEmpty()) {
            note.setCategory(request.getCategory());
        }
        note = noteRepository.save(note); // 保存到MySQL

        // 新增笔记后清除用户级的笔记列表缓存（列表数据已变）
        cacheProtection.evict(CACHE_NOTE_LIST + userId);

        try {
            vectorStoreService.addNoteVector(note);
        } catch (Exception e) {
            log.warn("Failed to add note vector to ChromaDB: {}", e.getMessage());
        }

        // 事务提交后再执行异步任务，否则异步线程看不到未提交的笔记
        String noteId = note.getId();
        String category = request.getCategory();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.asyncAutoTagAndReview(noteId, userId, category);
            }
        });

        return toResponse(note);
    }

    @Async("taskExecutor") // 使用异步线程
    public void asyncAutoTagAndReview(String noteId, String userId, String userCategory) {
        try {
            Note note = noteRepository.findById(noteId).orElse(null);
            if (note == null) return;

            // 调用 LLM 自动生成标签和分类
            try {
                String content = note.getContent() != null ? note.getContent() : "";
                String preview = content.length() > 1000 ? content.substring(0, 1000) : content;

                String categoryHint = (userCategory != null && !userCategory.isEmpty())
                        ? "用户已选择分类为「" + userCategory + "」，请直接使用该分类。"
                        : "从 [work, study, life, project] 中选一个最匹配的（work=工作, study=学习, life=生活, project=项目）。";

                String prompt = "请根据以下笔记内容，返回一个JSON格式的分类结果。\n" +
                        "要求：\n" +
                        "1. category：" + categoryHint + "\n" +
                        "2. tags：返回2-4个标签关键词（简短的中文词语）\n" +
                        "只返回JSON，不要其他文字。格式：{\"category\":\"work\",\"tags\":[\"xxx\",\"xxx\"]}\n\n" +
                        "笔记标题：" + note.getTitle() + "\n" +
                        "笔记内容：" + preview;

                ChatLanguageModel chatModel = modelFactory.createChatModel();
                Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
                String result = response.content().text();

                // 解析 LLM 返回的 JSON
                String category = parseCategory(result);
                List<String> tags = parseTags(result);

                // 如果用户指定了分类，优先使用用户的选择
                if (userCategory != null && !userCategory.isEmpty()) {
                    category = userCategory;
                }

                note.setCategory(category);
                note.setTags(tags.isEmpty() ? List.of("笔记") : tags);
                noteRepository.save(note);

                log.debug("Auto-tagged note {}: category={}, tags={}", noteId, category, tags);
            } catch (Exception e) {
                log.warn("LLM auto-tag failed for note {}, using defaults: {}", noteId, e.getMessage());
                note.setTags(List.of("笔记"));
                note.setCategory(userCategory != null ? userCategory : "study");
                noteRepository.save(note);
            }

            // 创建复习记录
            ReviewRecord record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString().replace("-", ""));
            record.setNoteId(noteId);
            record.setUserId(userId);
            record.setNextReviewAt(LocalDateTime.now().plusDays(1));
            record.setIntervalDays(1);
            record.setReviewCount(0);
            reviewRecordRepository.save(record);

            log.debug("Created review record for note {}", noteId);
        } catch (Exception e) {
            log.error("Failed to auto-tag note {}: {}", noteId, e.getMessage());
        }
    }

    private String parseCategory(String llmResponse) {
        try {
            int start = llmResponse.indexOf("\"category\"");
            if (start == -1) return "study";
            int colon = llmResponse.indexOf(":", start);
            int quoteStart = llmResponse.indexOf("\"", colon + 1);
            int quoteEnd = llmResponse.indexOf("\"", quoteStart + 1);
            String category = llmResponse.substring(quoteStart + 1, quoteEnd).trim();
            if (List.of("work", "study", "life", "project").contains(category)) {
                return category;
            }
            return "study";
        } catch (Exception e) {
            return "study";
        }
    }

    private List<String> parseTags(String llmResponse) {
        try {
            int start = llmResponse.indexOf("\"tags\"");
            if (start == -1) return List.of();
            int bracketStart = llmResponse.indexOf("[", start);
            int bracketEnd = llmResponse.indexOf("]", bracketStart);
            String arrayStr = llmResponse.substring(bracketStart + 1, bracketEnd);
            List<String> tags = new ArrayList<>();
            for (String item : arrayStr.split(",")) {
                String tag = item.trim().replace("\"", "");
                if (!tag.isEmpty()) {
                    tags.add(tag);
                }
            }
            return tags;
        } catch (Exception e) {
            return List.of();
        }
    }

    public NoteListResponse listNotes(String userId, int page, int pageSize, String category, String tag) {
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt"));

        Page<Note> notePage;
        if (category != null && !category.isEmpty()) {
            notePage = noteRepository.findByUserIdAndCategoryOrderByUpdatedAtDesc(userId, category, pageRequest);
        } else {
            notePage = noteRepository.findByUserIdOrderByUpdatedAtDesc(userId, pageRequest);
        }

        List<Note> notes = notePage.getContent();

        if (tag != null && !tag.isEmpty()) {
            notes = notes.stream()
                    .filter(n -> n.getTags() != null && n.getTags().contains(tag))
                    .collect(Collectors.toList());
        }

        List<NoteResponse> responses = notes.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return new NoteListResponse(responses, notePage.getTotalElements());
    }

    public NoteResponse getNote(String userId, String noteId) {
        Note note = cacheProtection.getWithProtection(
                CACHE_NOTE + noteId, Note.class, TTL_NOTE,
                () -> noteRepository.findById(noteId).orElse(null)
        );
        if (note == null) {
            throw new BusinessException("笔记不存在");
        }
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该笔记");
        }
        return toResponse(note);
    }

    @Transactional
    public NoteResponse updateNote(String userId, String noteId, NoteUpdate request) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException("笔记不存在"));
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权修改该笔记");
        }

        boolean contentChanged = false;
        if (request.getTitle() != null) {
            note.setTitle(request.getTitle());
        }
        if (request.getContent() != null && !request.getContent().equals(note.getContent())) {
            note.setContent(request.getContent());
            contentChanged = true;
        }

        note = noteRepository.save(note);

        // 更新笔记后清除缓存
        cacheProtection.evict(CACHE_NOTE + noteId);
        cacheProtection.evict(CACHE_NOTE_LIST + userId);

        if (contentChanged) {
            try {
                vectorStoreService.deleteNoteVector(noteId, note.getUserId());
                vectorStoreService.addNoteVector(note);
            } catch (Exception e) {
                log.warn("Failed to update note vector in ChromaDB: {}", e.getMessage());
            }
        }

        return toResponse(note);
    }

    @Transactional
    public void deleteNote(String userId, String noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException("笔记不存在"));
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权删除该笔记");
        }

        // 删除笔记
        noteRepository.delete(note);

        // 删除笔记后清除缓存
        cacheProtection.evict(CACHE_NOTE + noteId);
        cacheProtection.evict(CACHE_NOTE_LIST + userId);

        // 删除关联的复习记录（使用原生 SQL，绕过 Hibernate 实体追踪）
        try {
            int deleted = reviewRecordRepository.deleteByNoteIdNative(noteId);
            log.debug("删除复习记录: noteId={}, count={}", noteId, deleted);
        } catch (Exception e) {
            log.warn("删除复习记录失败: {}", e.getMessage());
        }

        // 事务提交后再删除向量（避免向量操作失败导致事务回滚）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    vectorStoreService.deleteNoteVector(noteId, userId);
                } catch (Exception e) {
                    log.warn("Failed to delete note vector after commit: {}", e.getMessage());
                }
            }
        });
    }

    public NoteListResponse searchNotes(String userId, String query) {
        // 按空格拆分关键词，任意一个词匹配即可（OR 逻辑）
        // 例如 "长江三峡 笔记" → 匹配包含"长江三峡"或"笔记"的笔记
        String[] keywords = query.trim().split("\\s+");
        List<Note> matched;
        if (keywords.length >= 2) {
            matched = noteRepository.searchByTwoKeywords(userId, keywords[0], keywords[1]);
        } else {
            matched = noteRepository.searchByKeyword(userId, query.trim());
        }
        List<NoteResponse> notes = matched.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return new NoteListResponse(notes, notes.size());
    }

    public Map<String, Object> getStats(String userId) {
        long total = noteRepository.countByUserId(userId);
        List<Object[]> categoryData = noteRepository.countByCategoryGrouped(userId);
        long uncategorized = noteRepository.countUncategorized(userId);

        List<Map<String, Object>> categories = categoryData.stream()
                .map(row -> Map.<String, Object>of(
                        "category", row[0] != null ? row[0] : "uncategorized",
                        "count", row[1]
                ))
                .collect(Collectors.toList());

        return Map.of(
                "total", total,
                "categories", categories,
                "uncategorized", uncategorized
        );
    }

    public List<RelatedNoteItem> getRelatedNotes(String userId, String noteId, int topK) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException("笔记不存在"));

        List<RelatedNoteItem> results = new ArrayList<>();

        // Search in notes collection
        String content = note.getContent() != null ? note.getContent() : "";
        List<Map<String, Object>> noteResults = vectorStoreService.searchNotes(
                userId, note.getTitle() + " " + content.substring(0, Math.min(200, content.length())), topK + 1);

        for (Map<String, Object> r : noteResults) {
            String foundId = (String) r.get("note_id");
            if (foundId != null && !foundId.equals(noteId)) {
                noteRepository.findById(foundId).ifPresent(n -> results.add(new RelatedNoteItem(
                        n.getId(), n.getTitle(),
                        n.getContent().substring(0, Math.min(150, n.getContent().length())),
                        null,
                        ((Number) r.getOrDefault("distance", 0f)).floatValue(),
                        "note"
                )));
            }
        }

        return results.stream().sorted(Comparator.comparingDouble(RelatedNoteItem::similarity)).limit(topK).collect(Collectors.toList());
    }

    public Map<String, Object> exportNote(String userId, String noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException("笔记不存在"));
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该笔记");
        }

        StringBuilder md = new StringBuilder();
        md.append("---\ntitle: ").append(note.getTitle()).append("\n");
        if (note.getTags() != null) {
            md.append("tags: ").append(note.getTags()).append("\n");
        }
        if (note.getCategory() != null) {
            md.append("category: ").append(note.getCategory()).append("\n");
        }
        md.append("---\n\n# ").append(note.getTitle()).append("\n\n").append(note.getContent());

        return Map.of(
                "markdown", md.toString(),
                "filename", noteId + ".md"
        );
    }

    private NoteResponse toResponse(Note note) {
        return new NoteResponse(
                note.getId(), note.getUserId(), note.getTitle(), note.getContent(),
                note.getTags(), note.getCategory(), note.getCreatedAt(), note.getUpdatedAt()
        );
    }
}
