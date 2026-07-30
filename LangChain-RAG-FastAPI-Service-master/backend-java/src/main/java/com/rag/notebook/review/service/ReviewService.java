package com.rag.notebook.review.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.cache.CacheProtectionService;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.repo.NoteRepository;
import com.rag.notebook.review.dto.ReviewDoneResponse;
import com.rag.notebook.review.dto.ReviewResponse;
import com.rag.notebook.review.entity.ReviewRecord;
import com.rag.notebook.review.repo.ReviewRecordRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewService {

    private static final int[] INTERVALS = {1, 2, 4, 7, 15, 30};

    private static final String QUESTION_PROMPT = "请根据以下笔记内容，生成一道单选题用于复习。\n" +
            "要求：\n" +
            "1. 题目必须考察笔记中的核心知识点、概念或事实\n" +
            "2. 有且仅有4个选项，只有1个正确答案\n" +
            "3. 错误选项要有迷惑性，不能太明显\n" +
            "4. 不要问\"出自哪篇笔记\"、\"笔记标题是什么\"等与笔记元信息相关的问题\n" +
            "5. 题目和选项内容要简洁，不要包含引号或特殊字符\n\n" +
            "严格按以下格式返回，不要有任何多余文字、不要用markdown代码块：\n" +
            "question: 题目内容\n" +
            "A: 选项A内容\n" +
            "B: 选项B内容\n" +
            "C: 选项C内容\n" +
            "D: 选项D内容\n" +
            "answer: 正确选项字母\n\n" +
            "笔记标题：{title}\n" +
            "笔记内容：\n{content}";

    private final ReviewRecordRepository reviewRecordRepository;
    private final NoteRepository noteRepository;
    private final ModelFactory modelFactory;
    private final Executor taskExecutor;
    private final CacheProtectionService cacheProtection;

    public ReviewService(ReviewRecordRepository reviewRecordRepository, NoteRepository noteRepository,
                         ModelFactory modelFactory, CacheProtectionService cacheProtection,
                         @org.springframework.beans.factory.annotation.Qualifier("taskExecutor") Executor taskExecutor) {
        this.reviewRecordRepository = reviewRecordRepository;
        this.noteRepository = noteRepository;
        this.modelFactory = modelFactory;
        this.cacheProtection = cacheProtection;
        this.taskExecutor = taskExecutor;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getTodayReviews(String userId) {
        // 缓存防护：复习列表 TTL 10 分钟，数据变更时（markReviewed）自动清除缓存
        return cacheProtection.getWithProtection(
                "review:today:" + userId, Map.class, 600L,
                () -> buildTodayReviews(userId)
        );
    }

    /**
     * 从 DB 构建今日复习列表（被缓存保护包裹）。
     */
    private Map<String, Object> buildTodayReviews(String userId) {
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

        // 复习进度已变，清除今日复习列表缓存
        cacheProtection.evict("review:today:" + userId);

        return new ReviewDoneResponse(
                true, "已标记回顾",
                record.getReviewCount(),
                record.getIntervalDays(),
                record.getNextReviewAt()
        );
    }

    /**
     * 异步生成复习选择题：Tomcat 线程立即释放，LLM 调用在 taskExecutor 线程中执行
     */
    public CompletableFuture<Map<String, Object>> generateQuestion(String userId, String noteId) {
        // 同步校验（在 Tomcat 线程中执行，快速失败）
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new BusinessException("笔记不存在"));
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该笔记");
        }

        String title = note.getTitle() != null ? note.getTitle() : "无标题";
        String content = note.getContent() != null ? note.getContent() : "";
        String preview = content.length() > 2000 ? content.substring(0, 2000) : content;

        // 异步调用 LLM（在 taskExecutor 线程中执行）
        return CompletableFuture.supplyAsync(() -> {
            ChatLanguageModel chatModel = modelFactory.createChatModel();
            String prompt = QUESTION_PROMPT
                    .replace("{title}", title)
                    .replace("{content}", preview);

            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            String result = response.content().text();
            log.debug("LLM 返回选择题原始内容: {}", truncate(result, 300));

            return parseQuestionResponse(result);
        }, taskExecutor);
    }

    /**
     * 解析 LLM 返回的选择题
     * 期望格式（每行一个字段，比 JSON 更可靠）：
     *   question: 题目内容
     *   A: 选项A
     *   B: 选项B
     *   C: 选项C
     *   D: 选项D
     *   answer: B
     *
     * 同时兼容 JSON 格式作为兜底
     */
    private Map<String, Object> parseQuestionResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            throw new RuntimeException("LLM 返回为空");
        }

        String text = cleanResponse(llmResponse);

        // 优先按行格式解析（更可靠）
        Map<String, Object> result = parseLineFormat(text);
        if (result != null) return result;

        // 兜底：尝试按 JSON 解析
        result = parseJsonFormat(text);
        if (result != null) return result;

        log.warn("解析选择题失败, 原始响应: {}", truncate(llmResponse, 300));
        throw new RuntimeException("选择题解析失败");
    }

    /**
     * 按行格式解析：question: xxx / A: xxx / B: xxx / ...
     */
    private Map<String, Object> parseLineFormat(String text) {
        try {
            String[] lines = text.split("\n");
            String question = null;
            String[] choices = new String[4];
            String answer = null;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.toLowerCase().startsWith("question:") || trimmed.toLowerCase().startsWith("题目:")) {
                    question = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                } else if (trimmed.matches("^[Aa]\\s*[:：].*")) {
                    choices[0] = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    if (choices[0].startsWith(":")) choices[0] = choices[0].substring(1).trim();
                } else if (trimmed.matches("^[Bb]\\s*[:：].*")) {
                    choices[1] = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    if (choices[1].startsWith(":")) choices[1] = choices[1].substring(1).trim();
                } else if (trimmed.matches("^[Cc]\\s*[:：].*")) {
                    choices[2] = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    if (choices[2].startsWith(":")) choices[2] = choices[2].substring(1).trim();
                } else if (trimmed.matches("^[Dd]\\s*[:：].*")) {
                    choices[3] = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    if (choices[3].startsWith(":")) choices[3] = choices[3].substring(1).trim();
                } else if (trimmed.toLowerCase().startsWith("answer:") || trimmed.toLowerCase().startsWith("答案:")) {
                    answer = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                }
            }

            if (question == null || choices[0] == null || choices[1] == null ||
                    choices[2] == null || choices[3] == null) {
                return null;
            }

            // 解析答案字母，映射为选项内容
            String answerContent = resolveAnswer(answer, choices);

            return Map.of(
                    "question", question,
                    "choices", List.of(choices[0], choices[1], choices[2], choices[3]),
                    "answer", answerContent
            );
        } catch (Exception e) {
            log.debug("行格式解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 兜底：尝试按 JSON 格式解析（处理 LLM 返回 JSON 的情况）
     */
    private Map<String, Object> parseJsonFormat(String text) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> json = mapper.readValue(text, new TypeReference<>() {});

            String question = (String) json.get("question");
            if (question == null || question.isEmpty()) return null;

            Object choicesObj = json.get("choices");
            List<String> choices;
            if (choicesObj instanceof List<?> list) {
                choices = list.stream().map(Object::toString).collect(Collectors.toList());
            } else {
                return null;
            }
            if (choices.size() < 4) return null;

            String answer = json.get("answer") != null ? json.get("answer").toString() : choices.get(0);
            String answerContent = resolveAnswer(answer, choices.toArray(new String[0]));

            return Map.of(
                    "question", question,
                    "choices", choices.subList(0, 4),
                    "answer", answerContent
            );
        } catch (Exception e) {
            log.debug("JSON 格式解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将答案字母（A/B/C/D）映射为对应的选项内容，如果已经是内容则直接返回
     */
    private String resolveAnswer(String answer, String[] choices) {
        if (answer == null || answer.isEmpty()) return choices[0];
        String trimmed = answer.toUpperCase().trim();
        if (trimmed.matches("^[A-D]$")) {
            return choices[trimmed.charAt(0) - 'A'];
        }
        return answer;
    }

    /**
     * 清理 LLM 响应：去掉 markdown 代码块、前后空白
     */
    private String cleanResponse(String response) {
        if (response == null) return "";
        String trimmed = response.trim();
        // 去掉 ```json ... ``` 或 ``` ... ```
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }
        return trimmed;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
