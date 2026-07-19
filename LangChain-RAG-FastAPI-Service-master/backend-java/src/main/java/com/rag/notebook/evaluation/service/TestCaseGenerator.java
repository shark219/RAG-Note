package com.rag.notebook.evaluation.service;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.evaluation.entity.TestCase;
import com.rag.notebook.evaluation.repository.TestCaseRepository;
import com.rag.notebook.knowledge.entity.KnowledgeDocument;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentRepository;
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
    private final TestCaseRepository testCaseRepository;

    public TestCaseGenerator(ModelFactory modelFactory,
                             KnowledgeDocumentRepository documentRepository,
                             TestCaseRepository testCaseRepository) {
        this.modelFactory = modelFactory;
        this.documentRepository = documentRepository;
        this.testCaseRepository = testCaseRepository;
    }

    /**
     * 从用户的知识库文档自动生成测试用例
     *
     * @param userId 目标用户
     * @param count  目标生成数量
     * @return 实际生成的测试用例数
     */
    public int generateTestCases(String userId, int count) {
        List<KnowledgeDocument> docs = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (docs.isEmpty()) {
            log.warn("用户 {} 没有知识库文档，无法生成测试用例", userId);
            return 0;
        }

        ChatLanguageModel model = modelFactory.createBalancedModel();
        int generated = 0;

        for (KnowledgeDocument doc : docs) {
            if (generated >= count) break;

            String content = doc.getPreview();
            if (content == null || content.length() < 50) continue;

            try {
                // Step 1: 提取知识点
                String keyPoints = extractKeyPoints(model, content);
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
                        String groundTruth = generateGroundTruth(model, content, question);
                        if (groundTruth == null || groundTruth.isBlank()) continue;

                        // Step 4: 保存
                        TestCase testCase = new TestCase();
                        testCase.setUserId(userId);
                        testCase.setQuestion(question.trim());
                        testCase.setGroundTruth(groundTruth.trim());
                        testCase.setDocId(doc.getId());
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
}
