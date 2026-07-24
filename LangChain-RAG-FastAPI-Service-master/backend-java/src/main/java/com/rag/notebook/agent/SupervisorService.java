package com.rag.notebook.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Supervisor Agent：分析查询复杂度，拆分为子任务
 *
 * 职责：
 * 1. 判断查询是否需要多 Agent 流水线
 * 2. 需要时将查询拆分为 2-4 个可并行执行的子任务
 * 3. 每个子任务有明确的描述和工具建议
 */
@Slf4j
@Service
public class SupervisorService {

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SupervisorService(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 分析查询，决定是否需要多 Agent 流水线
     *
     * @param query 用户查询
     * @return 子任务列表。空列表表示查询简单，应走单 Agent。
     */
    public List<SubTask> plan(String query) {
        try {
            String template = loadPrompt("prompt/supervisor_plan.txt");
            String prompt = template.replace("{query}", query);

            ChatLanguageModel chatModel = modelFactory.createPreciseModel();
            List<ChatMessage> messages = List.of(
                    SystemMessage.from("你是任务规划器。分析用户查询，判断是否需要拆分为多个子任务。\n\n"
                            + "重要输出规则：\n"
                            + "1. 你只能返回纯净JSON数组，禁止任何前置/后置中文说明、禁止\"引用：\"、禁止解释文字、禁止markdown```json标记\n"
                            + "2. 不要输出任何自然语言，结果外层不包裹任何内容\n"
                            + "3. 如果需要引用消息信息，把引用信息作为JSON内部字段，不能放在JSON外面"),
                    UserMessage.from(prompt)
            );

            Response<AiMessage> response = chatModel.generate(messages);
            String raw = response.content().text().trim();
            log.info("Supervisor 原始返回: {}", raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);

            // 清洗 LLM 返回文本，提取纯净 JSON
            String cleaned = cleanLlmJson(raw);

            JsonNode array;
            try {
                array = objectMapper.readTree(cleaned);
            } catch (Exception e) {
                // 清洗后仍解析失败，尝试括号计数提取
                String extracted = extractJsonArray(raw);
                if (extracted == null) {
                    log.warn("Supervisor 无法提取 JSON，走单 Agent: {}", e.getMessage());
                    return Collections.emptyList();
                }
                array = objectMapper.readTree(extracted);
            }

            if (!array.isArray() || array.isEmpty()) {
                return Collections.emptyList();
            }

            List<SubTask> subTasks = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JsonNode node = array.get(i);
                SubTask task = new SubTask();
                task.setId("R-" + (i + 1));
                task.setLabel(node.has("label") ? node.get("label").asText() : "子任务" + (i + 1));
                task.setDescription(node.has("description") ? node.get("description").asText() : "");
                task.setToolHint(node.has("tool") ? node.get("tool").asText() : null);
                task.setMustUseTool(node.has("mustUseTool") && node.get("mustUseTool").asBoolean());
                subTasks.add(task);
            }

            log.info("Supervisor: 拆分为 {} 个子任务", subTasks.size());
            for (SubTask st : subTasks) {
                log.info("  [{}] {} - {}", st.getId(), st.getLabel(), truncate(st.getDescription(), 50));
            }

            return subTasks;

        } catch (Exception e) {
            log.warn("Supervisor 规划失败，走单 Agent: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 清洗 LLM 返回文本，提取内嵌 JSON
     * 处理模型擅自添加的中文前缀（引用：、答案：等）、markdown 代码块标记
     */
    private String cleanLlmJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String text = raw.trim();

        // 移除 markdown ```json 标记
        text = text.replaceAll("^```json\\s*", "");
        text = text.replaceAll("^```\\s*", "");
        text = text.replaceAll("```\\s*$", "");

        // 移除常见中文前缀，直到找到第一个 { 或 [
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        text = text.substring(start);

        // 截断到最后一个 } 或 ]
        int end = text.length() - 1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}' || c == ']') {
                end = i;
                break;
            }
        }
        return text.substring(0, end + 1).trim();
    }

    /**
     * 用括号计数精确提取第一个 JSON 数组
     */
    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
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
