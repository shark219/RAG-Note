package com.rag.notebook.evaluation.service;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.evaluation.entity.TestCase;
import com.rag.notebook.evaluation.repository.TestCaseRepository;
import com.rag.notebook.knowledge.entity.KnowledgeDocument;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentRepository;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.repo.NoteRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class TestCaseGenerator {

    private final ModelFactory modelFactory;
    private final KnowledgeDocumentRepository documentRepository;
    private final NoteRepository noteRepository;
    private final TestCaseRepository testCaseRepository;

    public TestCaseGenerator(ModelFactory modelFactory,
                             KnowledgeDocumentRepository documentRepository,
                             NoteRepository noteRepository,
                             TestCaseRepository testCaseRepository) {
        this.modelFactory = modelFactory;
        this.documentRepository = documentRepository;
        this.noteRepository = noteRepository;
        this.testCaseRepository = testCaseRepository;
    }

    /** 测试用例的来源（知识库文档或笔记） */
    private record Source(String id, String type, String content) {}

    /**
     * 从用户的知识库文档和笔记自动生成测试用例
     *
     * @param userId 目标用户
     * @param count  目标生成数量
     * @return 实际生成的测试用例数
     */
    public int generateTestCases(String userId, int count) {
        List<Source> sources = new ArrayList<>();
        List<KnowledgeDocument> docs = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (KnowledgeDocument doc : docs) {
            if (doc.getPreview() != null && doc.getPreview().length() >= 50) {
                sources.add(new Source(doc.getId(), "doc", doc.getPreview()));
            }
        }
        List<Note> notes = noteRepository.findAllByUserId(userId);
        for (Note note : notes) {
            if (note.getContent() != null && note.getContent().length() >= 50) {
                sources.add(new Source(note.getId(), "note", note.getContent()));
            }
        }

        if (sources.isEmpty()) {
            log.warn("用户 {} 没有知识库文档或笔记，无法生成测试用例", userId);
            return 0;
        }

        ChatLanguageModel model = modelFactory.createBalancedModel();
        int generated = 0;

        for (Source source : sources) {
            if (generated >= count) break;

            try {
                // Step 1: 提取知识点
                String keyPoints = extractKeyPoints(model, source.content());
                if (keyPoints == null || keyPoints.isBlank()) continue;

                String[] points = keyPoints.split("\n");
                for (String point : points) {
                    if (generated >= count) break;
                    point = point.trim();
                    if (point.isEmpty() || point.length() < 5) continue;

                    try {
                        // Step 2: 生成问题
                        String question = generateQuestion(model, point);
                        if (question == null || question.isBlank()) continue;

                        // Step 3: 生成标准答案
                        String groundTruth = generateGroundTruth(model, source.content(), question);
                        if (groundTruth == null || groundTruth.isBlank()) continue;

                        // Step 4: 保存
                        TestCase testCase = new TestCase();
                        testCase.setUserId(userId);
                        testCase.setQuestion(question.trim());
                        testCase.setGroundTruth(groundTruth.trim());
                        testCase.setSourceType(source.type());
                        if ("note".equals(source.type())) {
                            testCase.setNoteId(source.id());
                        } else {
                            testCase.setDocId(source.id());
                        }
                        testCase.setDifficulty("simple");
                        testCaseRepository.save(testCase);

                        generated++;
                        log.info("生成测试用例 {}/{}: {}", generated, count, question.substring(0, Math.min(30, question.length())));
                    } catch (Exception e) {
                        log.warn("生成测试用例失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("提取知识点失败: {}", e.getMessage());
            }
        }

        log.info("测试用例生成完成: 用户={}, 生成{}条", userId, generated);
        return generated;
    }

    /**
     * 手动新增测试用例：根据用户填写的 question，直接基于所选来源内容生成标准答案
     */
    public TestCase createManualTestCase(String userId, String question, String sourceType, String docId, String noteId) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        log.info("手动新增测试用例: 用户={}, question={}, 来源类型={}, docId={}, noteId={}",
                userId, truncate(question, 30), sourceType, docId, noteId);
        String content = loadSourceContent(userId, sourceType, docId, noteId);
        String groundTruth = generateGroundTruth(modelFactory.createBalancedModel(), content, question);
        if (groundTruth == null || groundTruth.isBlank()) {
            throw new BusinessException("标准答案生成失败，请稍后重试");
        }

        TestCase testCase = new TestCase();
        testCase.setUserId(userId);
        testCase.setQuestion(question.trim());
        testCase.setGroundTruth(groundTruth.trim());
        testCase.setSourceType("note".equals(sourceType) ? "note" : "doc");
        if ("note".equals(sourceType)) {
            testCase.setNoteId(noteId);
        } else {
            testCase.setDocId(docId);
        }
        testCase.setDifficulty("simple");
        testCaseRepository.save(testCase);
        log.info("手动新增测试用例保存成功: id={}, 用户={}, 来源={}", testCase.getId(), userId, sourceType);
        return testCase;
    }

    /**
     * 编辑测试用例：更新 question 与来源，直接基于所选来源内容重新生成标准答案
     */
    public TestCase updateManualTestCase(String userId, Long id, String question, String sourceType, String docId, String noteId) {
        TestCase testCase = testCaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "测试用例不存在"));
        if (!testCase.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权编辑他人的测试用例");
        }
        if (question == null || question.isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        log.info("编辑测试用例: id={}, 用户={}, question={}, 来源类型={}, docId={}, noteId={}",
                id, userId, truncate(question, 30), sourceType, docId, noteId);
        String content = loadSourceContent(userId, sourceType, docId, noteId);
        String groundTruth = generateGroundTruth(modelFactory.createBalancedModel(), content, question);
        if (groundTruth == null || groundTruth.isBlank()) {
            throw new BusinessException("标准答案生成失败，请稍后重试");
        }

        testCase.setQuestion(question.trim());
        testCase.setGroundTruth(groundTruth.trim());
        testCase.setSourceType("note".equals(sourceType) ? "note" : "doc");
        testCase.setDocId("note".equals(sourceType) ? null : docId);
        testCase.setNoteId("note".equals(sourceType) ? noteId : null);
        testCaseRepository.save(testCase);
        log.info("编辑测试用例保存成功: id={}, 用户={}, 来源={}", id, userId, sourceType);
        return testCase;
    }

    /**
     * 根据来源类型加载文档预览或笔记正文
     */
    private String loadSourceContent(String userId, String sourceType, String docId, String noteId) {
        if ("note".equals(sourceType)) {
            if (noteId == null || noteId.isBlank()) {
                throw new BusinessException("请选择笔记");
            }
            Note note = noteRepository.findById(noteId)
                    .filter(n -> n.getUserId().equals(userId))
                    .orElseThrow(() -> new BusinessException(404, "笔记不存在"));
            if (note.getContent() == null || note.getContent().isBlank()) {
                throw new BusinessException("该笔记内容为空，无法生成标准答案");
            }
            return note.getContent();
        }

        if (docId == null || docId.isBlank()) {
            throw new BusinessException("请选择知识库文档");
        }
        KnowledgeDocument doc = documentRepository.findById(docId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(404, "知识库文档不存在"));
        if (doc.getPreview() == null || doc.getPreview().isBlank()) {
            throw new BusinessException("该文档无可用内容，无法生成标准答案");
        }
        return doc.getPreview();
    }

    private String extractKeyPoints(ChatLanguageModel model, String content) {
        String truncated = content.length() > 1000 ? content.substring(0, 1000) : content;
        String prompt = "请从以下文档中提取 3 个关键知识点，每个知识点一行，不要编号，不要额外文字：\n\n" + truncated;
        return callLlm(model, prompt);
    }

    private String generateQuestion(ChatLanguageModel model, String keyPoint) {
        String prompt = "基于以下知识点，生成一个具体的问答问题。只返回问题，不要其他文字：\n\n知识点：" + keyPoint;
        return callLlm(model, prompt);
    }

    private String generateGroundTruth(ChatLanguageModel model, String docContent, String question) {
        String truncated = docContent.length() > 1500 ? docContent.substring(0, 1500) : docContent;
        String prompt = "基于以下文档内容，回答问题。只返回答案，不要其他文字：\n\n文档：" + truncated + "\n\n问题：" + question;
        return callLlm(model, prompt);
    }

    private String callLlm(ChatLanguageModel model, String prompt) {
        try {
            Response<AiMessage> response = model.generate(UserMessage.from(prompt));
            return response.content().text().trim();
        } catch (Exception e) {
            log.warn("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
}
