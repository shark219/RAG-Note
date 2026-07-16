package com.rag.notebook.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.chat.dto.ClarifyResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ClarifierService {

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            你是智能笔记助手的查询分析器。判断用户问题是否足够清晰，能否直接执行。

            判断标准：
            以下情况判定为"清晰"，直接执行：
            - 明确的笔记操作：搜索笔记、创建笔记、统计笔记、复习笔记
            - 具体的知识问题：有明确主题和范围（如"解释BM25算法"、"Transformer的注意力机制"）
            - 明确引用了自己的笔记或文档

            以下情况判定为"模糊"，需要澄清：
            - 主题过于宽泛（如"帮我学习AI"、"整理笔记"、"帮我看看"）
            - 意图不明确（如"这个怎么样"、"帮我优化"）
            - 缺少具体范围或角度

            你必须严格按以下JSON格式输出，不要输出任何其他内容：
            清晰时输出：{"is_clear":true,"research_brief":"精炼后的查询"}
            模糊时输出：{"is_clear":false,"message":"引导语","suggested_directions":["方向1","方向2","方向3"]}

            规则：
            1. 清晰时 research_brief 保留用户原始意图，仅做适当补充使其更具体
            2. 模糊时 suggested_directions 必须是3-4个具体的、可直接执行的笔记相关任务
            3. message 用亲切简洁的语气引导用户选择
            4. 输出语言与用户查询一致
            """;

    private static final Pattern JSON_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    public ClarifierService(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
        this.objectMapper = new ObjectMapper();
    }

    public ClarifyResult clarify(String query) {
        try {
            ChatLanguageModel chatModel = modelFactory.createPreciseModel();

            List<ChatMessage> messages = List.of(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from("用户查询：" + query)
            );

            Response<AiMessage> response = chatModel.generate(messages);
            String text = response.content().text();

            // 从回复中提取JSON
            Matcher matcher = JSON_PATTERN.matcher(text);
            if (!matcher.find()) {
                log.warn("Clarifier did not return valid JSON, treating as clear. Response: {}", text);
                return buildFallback(query);
            }

            JsonNode root = objectMapper.readTree(matcher.group());
            ClarifyResult result = new ClarifyResult();
            result.setClear(root.has("is_clear") && root.get("is_clear").asBoolean());
            result.setBrief(root.has("research_brief") ? root.get("research_brief").asText() : null);
            result.setMessage(root.has("message") ? root.get("message").asText() : null);

            List<String> directions = new ArrayList<>();
            if (root.has("suggested_directions")) {
                for (JsonNode dir : root.get("suggested_directions")) {
                    directions.add(dir.asText());
                }
            }
            result.setDirections(directions.isEmpty() ? Collections.emptyList() : directions);

            log.info("Clarify result: isClear={}, directions={}", result.isClear(),
                    result.isClear() ? "N/A" : result.getDirections().size());
            return result;

        } catch (Exception e) {
            log.error("Clarify failed: {}", e.getMessage());
            return buildFallback(query);
        }
    }

    private ClarifyResult buildFallback(String query) {
        ClarifyResult fallback = new ClarifyResult();
        fallback.setClear(true);
        fallback.setBrief(query);
        fallback.setDirections(Collections.emptyList());
        return fallback;
    }
}
