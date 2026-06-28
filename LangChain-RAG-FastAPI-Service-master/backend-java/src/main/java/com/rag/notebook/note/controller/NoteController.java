package com.rag.notebook.note.controller;

import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.note.dto.*;
import com.rag.notebook.note.service.NoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/note")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @PostMapping("/create")
    public ApiResponse<NoteResponse> createNote(@UserId String userId, @Valid @RequestBody NoteCreate request) {
        NoteResponse note = noteService.createNote(userId, request);
        return ApiResponse.success("笔记创建成功", note);
    }

    @GetMapping("/list")
    public ApiResponse<NoteListResponse> listNotes(
            @UserId String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag) {
        NoteListResponse result = noteService.listNotes(userId, page, pageSize, category, tag);
        return ApiResponse.success(result);
    }

    @GetMapping("/search")
    public ApiResponse<NoteListResponse> searchNotes(@UserId String userId, @RequestParam("q") String query) {
        NoteListResponse result = noteService.searchNotes(userId, query);
        return ApiResponse.success(result);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats(@UserId String userId) {
        Map<String, Object> stats = noteService.getStats(userId);
        return ApiResponse.success(stats);
    }

    @PostMapping("/autocomplete")
    public ApiResponse<Map<String, Object>> autocomplete(@UserId String userId, @RequestBody AutocompleteRequest request) {
        // Would call LLM for autocomplete - placeholder
        return ApiResponse.success(Map.of("success", true, "completion", ""));
    }

    @PostMapping("/assist/stream")
    public SseEmitter assistStream(@UserId String userId, @RequestBody AssistRequest request) {
        SseEmitter emitter = new SseEmitter(60000L);
        // Would call LLM for writing assist - placeholder that sends completion
        try {
            emitter.send(SseEmitter.event().data("[AI writing assist not yet implemented]"));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @PutMapping("/{noteId}")
    public ApiResponse<NoteResponse> updateNote(
            @UserId String userId, @PathVariable String noteId, @Valid @RequestBody NoteUpdate request) {
        NoteResponse note = noteService.updateNote(userId, noteId, request);
        return ApiResponse.success("笔记更新成功", note);
    }

    @DeleteMapping("/{noteId}")
    public ApiResponse<Void> deleteNote(@UserId String userId, @PathVariable String noteId) {
        noteService.deleteNote(userId, noteId);
        return ApiResponse.success("笔记删除成功");
    }

    @GetMapping("/{noteId}")
    public ApiResponse<NoteResponse> getNote(@UserId String userId, @PathVariable String noteId) {
        NoteResponse note = noteService.getNote(userId, noteId);
        return ApiResponse.success(note);
    }

    @PostMapping("/{noteId}/auto-tag")
    public ApiResponse<Void> autoTag(@UserId String userId, @PathVariable String noteId) {
        noteService.asyncAutoTagAndReview(noteId, userId);
        return ApiResponse.success("标签生成任务已提交");
    }

    @GetMapping("/{noteId}/related")
    public ApiResponse<List<RelatedNoteItem>> getRelatedNotes(
            @UserId String userId, @PathVariable String noteId) {
        List<RelatedNoteItem> related = noteService.getRelatedNotes(userId, noteId, 3);
        return ApiResponse.success(related);
    }

    @GetMapping("/{noteId}/export")
    public ApiResponse<Map<String, Object>> exportNote(@UserId String userId, @PathVariable String noteId) {
        Map<String, Object> result = noteService.exportNote(userId, noteId);
        return ApiResponse.success(result);
    }
}
