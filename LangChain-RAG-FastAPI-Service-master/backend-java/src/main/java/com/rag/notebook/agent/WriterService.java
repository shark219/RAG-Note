package com.rag.notebook.agent;

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
import java.util.List;
import java.util.Map;

/**
 * Writer Agent：合并多个子任务结果，生成最终回答
 *
 * 职责：
 * 1. 接收用户原始查询和各子任务的结果
 * 2. 去重、去矛盾、整合信息
 * 3. 生成结构化的最终回答
 */
@Slf4j
@Service
public class WriterService {

    private final ModelFactory modelFactory;

    public WriterService(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 合成最终回答
     *
     * @param query      用户原始查询
     * @param subTasks   子任务列表
     * @param results    各子任务结果：subTaskId → result
     * @return 合成后的最终回答
     */
    public String synthesize(String query, List<SubTask> subTasks, Map<String, String> results) {
        try {
            String template = loadPrompt("prompt/writer_synthesize.txt");

            StringBuilder findings = new StringBuilder();
            for (SubTask task : subTasks) {
                String result = results.get(task.getId());
                if (result != null && !result.isBlank()) {
                    findings.append("【").append(task.getLabel()).append("】\n");
                    findings.append(result).append("\n\n");
                }
            }

            String prompt = template
                    .replace("{query}", query)
                    .replace("{findings}", findings.toString());

            ChatLanguageModel chatModel = modelFactory.createBalancedModel();
            List<ChatMessage> messages = List.of(
                    SystemMessage.from("你是信息整合专家。将多个子任务的结果合并为一个连贯、完整的回答。"),
                    UserMessage.from(prompt)
            );

            Response<AiMessage> response = chatModel.generate(messages);
            String answer = response.content().text().trim();

            log.info("Writer: 合成完成，{} 个子任务 → {} 字回答", subTasks.size(), answer.length());
            return answer;

        } catch (Exception e) {
            log.warn("Writer 合成失败，使用拼接兜底: {}", e.getMessage());
            return fallbackMerge(subTasks, results);
        }
    }

    /**
     * 兜底合并：直接拼接各子任务结果
     */
    private String fallbackMerge(List<SubTask> subTasks, Map<String, String> results) {
        StringBuilder sb = new StringBuilder();
        for (SubTask task : subTasks) {
            String result = results.get(task.getId());
            if (result != null && !result.isBlank()) {
                sb.append("### ").append(task.getLabel()).append("\n\n");
                sb.append(result).append("\n\n");
            }
        }
        return sb.toString().trim();
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
}
