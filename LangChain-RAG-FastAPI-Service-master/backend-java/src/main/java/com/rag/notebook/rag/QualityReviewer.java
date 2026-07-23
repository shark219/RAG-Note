package com.rag.notebook.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.ModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 质量审查服务
 * 参考 sage-research 的 Supervisor Review 机制，
 * 在检索和生成两个阶段进行质量审查，不达标时触发重试。
 */
@Slf4j
@Service
public class QualityReviewer {

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    // 审查结果
    public record ReviewResult(boolean approved, String reason, String feedback) {}

    public QualityReviewer(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 审查检索到的文档质量
     *
     * @param query     用户查询
     * @param documents 检索到的文档列表
     * @return 审查结果，approved=false 时 feedback 包含改进建议（可用作重试查询）
     */
    public ReviewResult reviewRetrieval(String query, List<Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ReviewResult(false, "未检索到任何文档", query);
        }

        try {
            String template = loadPrompt("prompt/review_retrieval.txt");
            // 拼接文档内容，最多取前5篇
            StringBuilder docBuilder = new StringBuilder();
            int count = Math.min(5, documents.size());
            for (int i = 0; i < count; i++) {
                Map<String, Object> doc = documents.get(i);
                String content = (String) doc.getOrDefault("content", "");
                if (content.length() > 1500) content = content.substring(0, 1500);
                String title = (String) doc.getOrDefault("title", doc.getOrDefault("filename", "未知"));
                docBuilder.append("【").append(i + 1).append("】").append(title).append("\n").append(content).append("\n\n");
            }

            String prompt = template
                    .replace("{query}", query)
                    .replace("{documents}", docBuilder.toString());

            return callLlmForReview(prompt);
        } catch (Exception e) {
            log.warn("检索审查失败，默认通过: {}", e.getMessage());
            return new ReviewResult(true, "审查异常，默认通过", null);
        }
    }

    /**
     * 审查生成的回答质量
     *
     * @param query     用户查询
     * @param documents 参考文档
     * @param answer    生成的回答
     * @return 审查结果，approved=false 时 feedback 包含改进建议
     */
    public ReviewResult reviewAnswer(String query, List<Map<String, Object>> documents, String answer) {
        if (answer == null || answer.isBlank()) {
            return new ReviewResult(false, "回答为空", "请基于文档生成回答");
        }

        try {
            String template = loadPrompt("prompt/review_answer.txt");
            StringBuilder docBuilder = new StringBuilder();
            int count = Math.min(5, documents.size());
            for (int i = 0; i < count; i++) {
                Map<String, Object> doc = documents.get(i);
                String content = (String) doc.getOrDefault("content", "");
                if (content.length() > 1500) content = content.substring(0, 1500);
                docBuilder.append("【").append(i + 1).append("】").append(content).append("\n\n");
            }

            // 截断回答避免超长
            String truncatedAnswer = answer.length() > 3000 ? answer.substring(0, 3000) : answer;

            String prompt = template
                    .replace("{query}", query)
                    .replace("{documents}", docBuilder.toString())
                    .replace("{answer}", truncatedAnswer);

            return callLlmForReview(prompt);
        } catch (Exception e) {
            log.warn("回答审查失败，默认通过: {}", e.getMessage());
            return new ReviewResult(true, "审查异常，默认通过", null);
        }
    }

    /**
     * 根据审查反馈改写查询
     */
    public String rewriteQuery(String query, String feedback) {
        try {
            String template = loadPrompt("prompt/query_rewrite.txt");
            String prompt = template
                    .replace("{query}", query)
                    .replace("{feedback}", feedback);

            ChatLanguageModel chatModel = modelFactory.createPreciseModel();
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            String rewritten = response.content().text().trim();
            log.info("查询改写: '{}' → '{}'", truncate(query, 30), truncate(rewritten, 30));
            return rewritten;
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始查询: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 调用 LLM 获取审查结果
     */
    private ReviewResult callLlmForReview(String prompt) {
        try {
            ChatLanguageModel chatModel = modelFactory.createPreciseModel();
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            String text = response.content().text().trim();

            Matcher matcher = JSON_PATTERN.matcher(text);
            if (!matcher.find()) {
                log.warn("审查结果非JSON，默认通过: {}", truncate(text, 100));
                return new ReviewResult(true, "解析失败，默认通过", null);
            }

            JsonNode root = objectMapper.readTree(matcher.group());
            String verdict = root.has("verdict") ? root.get("verdict").asText() : "approved";
            String reason = root.has("reason") ? root.get("reason").asText() : "";
            String feedback = root.has("feedback") ? root.get("feedback").asText() : null;
            // 改进查询可能在 feedback 或 improved_query 字段
            if (feedback == null && root.has("improved_query")) {
                feedback = root.get("improved_query").asText();
            }

            boolean approved = "approved".equalsIgnoreCase(verdict);
            log.info("审查结果: verdict={}, reason={}", verdict, truncate(reason, 60));
            return new ReviewResult(approved, reason, feedback);
        } catch (Exception e) {
            log.warn("LLM 审查调用失败，默认通过: {}", e.getMessage());
            return new ReviewResult(true, "LLM调用失败，默认通过", null);
        }
    }

    private String loadPrompt(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("加载 prompt 失败: " + path, e);
        }
    }

    private String truncate(String str, int maxLen) {
        return str != null && str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
