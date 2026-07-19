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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern JSON_PATTERN = Pattern.compile("\\[.*]", Pattern.DOTALL);

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
                    SystemMessage.from("你是任务规划器。分析用户查询，判断是否需要拆分为多个子任务。"),
                    UserMessage.from(prompt)
            );

            Response<AiMessage> response = chatModel.generate(messages);
            String text = response.content().text().trim();

            // 提取 JSON 数组
            Matcher matcher = JSON_PATTERN.matcher(text);
            if (!matcher.find()) {
                log.info("Supervisor: 未返回子任务列表，走单 Agent");
                return Collections.emptyList();
            }

            JsonNode array = objectMapper.readTree(matcher.group());
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
