package com.rag.notebook.note.service;

import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.note.dto.*;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.repo.NoteRepository;
import com.rag.notebook.rag.VectorStoreService;
import com.rag.notebook.review.entity.ReviewRecord;
import com.rag.notebook.review.repo.ReviewRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NoteService {

    private final NoteRepository noteRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final VectorStoreService vectorStoreService;
    private final NoteService self;

    public NoteService(NoteRepository noteRepository,
                       ReviewRecordRepository reviewRecordRepository,
                       VectorStoreService vectorStoreService,
                       @Lazy NoteService self) {
        this.noteRepository = noteRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.vectorStoreService = vectorStoreService;
        this.self = self;
    }

    @Transactional
    public NoteResponse createNote(String userId, NoteCreate request) {
        Note note = new Note();
        note.setId(UUID.randomUUID().toString().replace("-", ""));
        note.setUserId(userId);
        note.setTitle(request.getTitle());
        note.setContent(request.getContent());
        note = noteRepository.save(note);

        try {
            vectorStoreService.addNoteVector(note);
        } catch (Exception e) {
            log.warn("Failed to add note vector to ChromaDB: {}", e.getMessage());
        }

        self.asyncAutoTagAndReview(note.getId(), userId);

        return toResponse(note);
    }

    @Async("taskExecutor")
    public void asyncAutoTagAndReview(String noteId, String userId) {
        try {
            Note note = noteRepository.findById(noteId).orElse(null);
            if (note == null) return;

            // Auto-tag would call LLM here - for now set defaults
            note.setTags(List.of("笔记"));
            note.setCategory("study");
            noteRepository.save(note);

            ReviewRecord record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString().replace("-", ""));
            record.setNoteId(noteId);
            record.setUserId(userId);
            record.setNextReviewAt(LocalDateTime.now().plusDays(1));
            record.setIntervalDays(1);
            record.setReviewCount(0);
            reviewRecordRepository.save(record);

            log.debug("Auto-tagged note {} and created review record", noteId);
        } catch (Exception e) {
            log.error("Failed to auto-tag note {}: {}", noteId, e.getMessage());
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
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException("笔记不存在"));
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

        if (contentChanged) {
            try {
                vectorStoreService.deleteNoteVector(noteId);
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

        noteRepository.delete(note);
        try {
            vectorStoreService.deleteNoteVector(noteId);
        } catch (Exception e) {
            log.warn("Failed to delete note vector from ChromaDB: {}", e.getMessage());
        }
    }

    public NoteListResponse searchNotes(String userId, String query) {
        List<Map<String, Object>> results = vectorStoreService.searchNotes(userId, query, 10);
        List<NoteResponse> notes = new ArrayList<>();

        for (Map<String, Object> result : results) {
            String noteId = (String) result.get("note_id");
            if (noteId == null) continue;
            noteRepository.findById(noteId).ifPresent(note -> notes.add(toResponse(note)));
        }

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
