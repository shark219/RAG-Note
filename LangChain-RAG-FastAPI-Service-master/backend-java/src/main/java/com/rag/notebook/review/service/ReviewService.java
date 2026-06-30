package com.rag.notebook.review.service;

import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.repo.NoteRepository;
import com.rag.notebook.review.dto.ReviewDoneResponse;
import com.rag.notebook.review.dto.ReviewResponse;
import com.rag.notebook.review.entity.ReviewRecord;
import com.rag.notebook.review.repo.ReviewRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewService {

    private static final int[] INTERVALS = {1, 2, 4, 7, 15, 30};

    private final ReviewRecordRepository reviewRecordRepository;
    private final NoteRepository noteRepository;

    public ReviewService(ReviewRecordRepository reviewRecordRepository, NoteRepository noteRepository) {
        this.reviewRecordRepository = reviewRecordRepository;
        this.noteRepository = noteRepository;
    }

    public Map<String, Object> getTodayReviews(String userId) {
        LocalDateTime now = LocalDateTime.now();
        List<ReviewRecord> dueReviews = reviewRecordRepository.findDueReviews(userId, now);

        List<ReviewResponse> reviews = dueReviews.stream()
                .map(record -> {
                    Note note = noteRepository.findById(record.getNoteId()).orElse(null);
                    if (note == null) return null;
                    String content = note.getContent() != null ? note.getContent() : "";
                    String preview = content.length() > 200
                            ? content.substring(0, 200) + "..."
                            : content;
                    return new ReviewResponse(
                            record.getId(), record.getNoteId(), note.getTitle(), preview,
                            note.getTags(), note.getCategory(), record.getReviewCount(),
                            record.getLastReviewedAt(), record.getIntervalDays()
                    );
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());

        return Map.of(
                "reviews", reviews,
                "total_count", reviews.size()
        );
    }

    @Transactional
    public ReviewDoneResponse markReviewed(String userId, String noteId) {
        ReviewRecord record = reviewRecordRepository.findByNoteIdAndUserId(noteId, userId)
                .orElse(null);

        if (record == null) {
            return new ReviewDoneResponse(false, "回顾记录不存在", null, null, null);
        }

        int newCount = record.getReviewCount() + 1;
        int intervalIndex = Math.min(newCount, INTERVALS.length - 1);
        int nextInterval = INTERVALS[intervalIndex];

        record.setReviewCount(newCount);
        record.setLastReviewedAt(LocalDateTime.now());
        record.setIntervalDays(nextInterval);
        record.setNextReviewAt(LocalDateTime.now().plusDays(nextInterval));
        record = reviewRecordRepository.save(record);

        return new ReviewDoneResponse(
                true, "已标记回顾",
                record.getReviewCount(),
                record.getIntervalDays(),
                record.getNextReviewAt()
        );
    }

    public Map<String, Object> generateQuestion(String userId, String noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException("笔记不存在"));
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该笔记");
        }

        String content = note.getContent();
        if (content == null) content = "";
        String preview = content.length() > 2000 ? content.substring(0, 2000) : content;

        // 根据笔记内容生成回顾问题
        String question = preview.isEmpty()
                ? "请回顾笔记「" + note.getTitle() + "」的主要内容"
                : "根据笔记「" + note.getTitle() + "」的内容，以下哪项描述是正确的？";

        return Map.of(
                "question", question,
                "choices", List.of("A. 选项A", "B. 选项B", "C. 选项C", "D. 选项D"),
                "answer", "A"
        );
    }
}
