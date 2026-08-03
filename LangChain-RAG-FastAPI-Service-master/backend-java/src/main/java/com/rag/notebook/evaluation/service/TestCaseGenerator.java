package com.rag.notebook.evaluation.service;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.evaluation.entity.TestCase;
import com.rag.notebook.evaluation.repository.TestCaseRepository;
import com.rag.notebook.knowledge.entity.KnowledgeDocument;
import com.rag.notebook.knowledge.entity.KnowledgeDocumentChunk;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentChunkRepository;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentRepository;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.entity.NoteChunk;
import com.rag.notebook.note.repo.NoteRepository;
import com.rag.notebook.note.repository.NoteChunkRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class TestCaseGenerator {

    private final ModelFactory modelFactory;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeDocumentChunkRepository chunkRepository;
    private final NoteRepository noteRepository;
    private final NoteChunkRepository noteChunkRepository;
    private final TestCaseRepository testCaseRepository;

    public TestCaseGenerator(ModelFactory modelFactory,
                             KnowledgeDocumentRepository documentRepository,
                             KnowledgeDocumentChunkRepository chunkRepository,
                             NoteRepository noteRepository,
                             NoteChunkRepository noteChunkRepository,
                             TestCaseRepository testCaseRepository) {
        this.modelFactory = modelFactory;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.noteRepository = noteRepository;
        this.noteChunkRepository = noteChunkRepository;
        this.testCaseRepository = testCaseRepository;
    }

    /**
     * 按 chunkIndex 顺序返回文档的全部 chunk 正文（不拼接），用于随机窗口采样；
     * KnowledgeDocument.preview 只存了第一个 chunk 的前200字，不能作为采样素材。
     */
    private List<String> loadDocumentChunkUnits(String documentId) {
        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        List<String> units = new ArrayList<>();
        for (KnowledgeDocumentChunk chunk : chunks) {
            if (chunk.getContent() != null && !chunk.getContent().isBlank()) {
                units.add(chunk.getContent());
            }
        }
        return units;
    }

    /** 拼接文档全部 chunk 得到完整正文，供手动新增/编辑测试用例场景使用（用户问题可能涉及全文任意位置） */
    private String loadFullDocumentContent(String documentId) {
        return String.join("\n\n", loadDocumentChunkUnits(documentId));
    }

    /**
     * 笔记优先取 NoteChunk 表的真实切片（向量化时生成，边界更贴合实际检索单元）；
     * NoteChunk 写入是在 try/catch 里做的（VectorStoreService.addNoteVector），
     * 切片失败或笔记是功能上线前创建的，可能查不到记录，这时退化为按空行分段兜底。
     */
    private List<String> loadNoteUnits(String noteId, String content) {
        List<NoteChunk> chunks = noteChunkRepository.findByNoteIdOrderByChunkIndexAsc(noteId);
        if (!chunks.isEmpty()) {
            List<String> units = new ArrayList<>();
            for (NoteChunk chunk : chunks) {
                if (chunk.getContent() != null && !chunk.getContent().isBlank()) {
                    units.add(chunk.getContent());
                }
            }
            if (!units.isEmpty()) {
                return units;
            }
        }
        return splitNoteIntoParagraphs(content);
    }

    /** 按空行切分段落，作为 NoteChunk 缺失时的兜底语义单元 */
    private List<String> splitNoteIntoParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        if (content == null) {
            return paragraphs;
        }
        for (String part : content.split("\\n\\s*\\n")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /**
     * 从随机起点开始，累积连续的 chunk/段落直到接近 maxChars，作为知识点提取和标准答案生成的共同素材。
     * 相比固定截取文档开头，能覆盖文档中后段的机制细节；按 chunk/段落边界累积，不会切断句子中间。
     */
    private String sampleWindow(List<String> units, int maxChars) {
        if (units == null || units.isEmpty()) {
            return "";
        }
        int startIdx = ThreadLocalRandom.current().nextInt(units.size());
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < units.size() && sb.length() < maxChars; i++) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(units.get(i));
        }
        return sb.toString();
    }

    /** 测试用例的来源（知识库文档或笔记），units 是按 chunk/段落切分的正文，用于随机窗口采样 */
    private record Source(String id, String type, List<String> units) {}

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
            List<String> units = loadDocumentChunkUnits(doc.getId());
            if (!units.isEmpty()) {
                sources.add(new Source(doc.getId(), "doc", units));
            }
        }
        List<Note> notes = noteRepository.findAllByUserId(userId);
        for (Note note : notes) {
            List<String> units = loadNoteUnits(note.getId(), note.getContent());
            if (!units.isEmpty()) {
                sources.add(new Source(note.getId(), "note", units));
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

            // 每个 source 只采样一次窗口，知识点提取、问题生成、标准答案生成全程复用同一段素材，
            // 避免 extractKeyPoints 和 generateGroundTruth 各自随机/截断到不同片段，导致答案和问题脱节。
            String window = sampleWindow(source.units(), 1000);
            if (window.isBlank()) continue;

            try {
                // Step 1: 提取知识点
                String keyPoints = extractKeyPoints(model, window);
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

                        // Step 3: 生成标准答案（复用与知识点提取相同的窗口，而非整篇文档）
                        String groundTruth = generateGroundTruth(model, window, question);
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
        String content = loadFullDocumentContent(doc.getId());
        if (content == null || content.isBlank()) {
            throw new BusinessException("该文档无可用内容，无法生成标准答案");
        }
        return content;
    }

    /** 出现这些描述性/任务性措辞，说明模型把"生成问题"的指令当成了要执行的任务复述了一遍 */
    private static final String[] INVALID_QUESTION_MARKERS = {
            "请根据", "设计一个问题", "知识点：", "**知识点**", "要求：", "**要求**", "输出："
    };

    /**
     * 防止某个chunk特别长导致窗口超出预期
     * 截断策略：长文档只取前半段会丢失中后段的核心机制/实现细节，改为前后各取一半，
     */
    private String truncateHeadAndTail(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        int half = maxChars / 2;
        String head = content.substring(0, half);
        String tail = content.substring(content.length() - half);
        return head + "\n...\n" + tail;
    }

    private String extractKeyPoints(ChatLanguageModel model, String content) {
        String truncated = truncateHeadAndTail(content, 1000);
        String prompt = "你是一个知识库评测集构建助手。\n"
                + "请从以下文档中提取3个用于生成问答测试的知识点。\n\n"
                + "要求：\n"
                + "1. 每个知识点必须包含明确的技术对象、核心机制或关键结论。\n"
                + "2. 不要提取过于宽泛的主题词（如“性能优化”、“系统设计”）。\n"
                + "3. 每个知识点一行输出。\n"
                + "4. 不要编号，不要解释，不要输出其他内容。\n\n"
                + "文档：\n" + truncated;
        return callLlm(model, prompt);
    }

    private String generateQuestion(ChatLanguageModel model, String keyPoint) {
        String prompt = "你是一个RAG评测数据生成助手。\n"
                + "根据下面的知识点，生成一个真实用户可能提出的问题。\n\n"
                + "严格要求：\n"
                + "1. 只输出最终的问题文本。\n"
                + "2. 禁止输出任务说明、生成过程或提示语。\n"
                + "3. 禁止出现“请根据以下知识点”“设计一个问题”“要求”等描述性文字。\n"
                + "4. 问题必须具体、可回答，并且答案可以从知识点对应文档中找到。\n"
                + "5. 问题应该考察对机制、原理、设计思路或应用场景的理解。\n\n"
                + "示例：\n"
                + "知识点：Redis通过跳表实现有序集合。\n"
                + "输出：Redis为什么选择跳表作为有序集合的数据结构？\n\n"
                + "知识点：" + keyPoint + "\n\n"
                + "输出：";

        String question = callLlm(model, prompt);
        if (question != null && isInvalidQuestion(question)) {
            log.warn("Query 生成疑似跑偏，重试一次: {}", truncate(question, 50));
            question = callLlm(model, prompt);
            if (question != null && isInvalidQuestion(question)) {
                log.warn("Query 生成重试后仍疑似跑偏，丢弃: {}", truncate(question, 50));
                return null;
            }
        }
        return question;
    }

    private boolean isInvalidQuestion(String question) {
        for (String marker : INVALID_QUESTION_MARKERS) {
            if (question.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 出现这些措辞，说明模型自认无法从素材中回答问题（常见于复合问题里有一半原文没提到），
     * 这类答案不是格式问题，重试没有意义，直接判定这条测试用例失败。 */
    private static final String[] UNANSWERABLE_MARKERS = {
            "无法确定", "未说明", "未提及", "文档未", "没有提到", "不明确", "无法从文档中"
    };

    private String generateGroundTruth(ChatLanguageModel model, String docContent, String question) {
        String truncated = truncateHeadAndTail(docContent, 1500);
        String prompt = "你是一个知识库问答评测答案生成助手。\n"
                + "请严格根据提供的文档内容回答问题。\n\n"
                + "要求：\n"
                + "1. 答案必须来自文档内容，不要引入外部信息。\n"
                + "2. 保留关键技术细节和设计原理。\n"
                + "3. 如果文档无法回答问题，请明确说明无法确定。\n"
                + "4. 只输出答案正文，不要解释生成过程，不要以“根据文档内容”“根据文档”等套话开头。\n\n"
                + "文档：\n" + truncated
                + "\n\n问题：" + question
                + "\n\n答案：";
        String groundTruth = callLlm(model, prompt);
        if (groundTruth != null && isUnanswerable(groundTruth)) {
            log.warn("标准答案疑似无法从素材回答，丢弃该测试用例: {}", truncate(groundTruth, 50));
            return null;
        }
        return groundTruth;
    }

    private boolean isUnanswerable(String groundTruth) {
        for (String marker : UNANSWERABLE_MARKERS) {
            if (groundTruth.contains(marker)) {
                return true;
            }
        }
        return false;
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
