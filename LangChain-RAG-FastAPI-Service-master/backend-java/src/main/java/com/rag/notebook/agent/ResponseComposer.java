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

/**
 * 最终回答生成器。
 *
 * 职责：根据已获得的干净证据，自然回答用户问题。
 *
 * 关键设计：
 * 1. 一次独立的 LLM 调用，无工具定义。
 * 2. 只看到 System Prompt（表达规则）+ userQuery + 结构化证据。
 * 3. 看不到 Supervisor Prompt、Reflection、Observation、errorCode 等内部上下文。
 * 4. 如果证据为空，诚实告知用户。
 */
@Slf4j
@Service
public class ResponseComposer {

    private final ModelFactory modelFactory;

    public ResponseComposer(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 根据证据生成最终自然回答
     *
     * @param pack    干净证据包
     * @param outcome AgentLoop 的执行结果（READY 或 MAX_ROUNDS）
     * @return 最终回答文本
     */
    public String compose(EvidencePack pack, AgentLoopResult.Outcome outcome) {
        String systemPrompt = loadComposePrompt();
        String evidenceBlock = buildEvidenceBlock(pack);
        String userMessage = buildUserMessage(pack.userQuery(), evidenceBlock, outcome);

        ChatLanguageModel llm = modelFactory.createBalancedModel();
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage)
        );

        Response<AiMessage> response = llm.generate(messages);
        String answer = response.content().text();

        if (answer == null || answer.isBlank()) {
            return fallbackAnswer(pack);
        }

        log.info("Composer: 生成回答 {} 字, 证据笔记 {} 篇",
                answer.length(), pack.notes().size());
        return answer.trim();
    }

    /**
     * 构建证据文本块
     */
    private String buildEvidenceBlock(EvidencePack pack) {
        StringBuilder sb = new StringBuilder();

        // 笔记证据
        if (!pack.notes().isEmpty()) {
            sb.append("【已获取的笔记内容】\n\n");
            for (EvidencePack.NoteEvidence note : pack.notes()) {
                sb.append("---\n");
                sb.append("标题：").append(note.title() != null ? note.title() : "无标题").append("\n");

                if (note.content() != null && !note.content().isBlank()) {
                    // 根据深度决定标签
                    String label = switch (note.depth()) {
                        case CONTENT_EVIDENCE -> "完整内容";
                        case SEARCH_EVIDENCE -> "内容预览";
                        case LIST_EVIDENCE -> "内容摘要";
                        default -> "内容";
                    };
                    sb.append(label).append("：\n").append(note.content()).append("\n");
                } else {
                    sb.append("（无内容）\n");
                }
                sb.append("\n");
            }
        }

        // 知识库摘要
        if (pack.knowledgeBaseSummary() != null) {
            sb.append("【知识库检索结果】\n");
            sb.append(pack.knowledgeBaseSummary()).append("\n\n");
        }

        // 笔记统计
        if (pack.noteStats() != null) {
            sb.append("【笔记统计】\n");
            sb.append(pack.noteStats()).append("\n");
        }

        // 今日复习
        if (pack.todayReviews() != null) {
            sb.append("【今日复习】\n");
            sb.append(pack.todayReviews()).append("\n");
        }

        // 写操作确认
        if (pack.writeConfirmation() != null) {
            sb.append("【操作结果】\n");
            sb.append(pack.writeConfirmation()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 构建给 Composer 的用户消息
     */
    private String buildUserMessage(String userQuery, String evidenceBlock,
                                     AgentLoopResult.Outcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(userQuery).append("\n\n");

        if (evidenceBlock.isBlank()) {
            sb.append("未获取到任何相关数据。请诚实地告诉用户没有找到相关内容，并建议用户尝试其他关键词或确认笔记/知识库中是否有相关数据。");
        } else {
            sb.append(evidenceBlock);
            sb.append("\n请根据以上信息回答用户的问题。");
        }

        if (outcome == AgentLoopResult.Outcome.MAX_ROUNDS) {
            sb.append("\n\n注意：本次检索达到了最大执行轮次，证据可能不完整。如果信息不足，请诚实说明。");
        }

        return sb.toString();
    }

    /**
     * 兜底回答
     */
    private String fallbackAnswer(EvidencePack pack) {
        if (pack.isEmpty()) {
            return "抱歉，我暂时无法处理你的请求。请稍后再试。";
        }
        // 简单拼接兜底
        StringBuilder sb = new StringBuilder();
        for (EvidencePack.NoteEvidence note : pack.notes()) {
            if (note.title() != null) {
                sb.append("### ").append(note.title()).append("\n\n");
            }
            if (note.content() != null) {
                sb.append(note.content()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private String loadComposePrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompt/compose_answer.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load compose_answer.txt, using default");
            return "你是最终回答生成器。根据已获得的信息，直接、自然地回答用户的问题。"
                    + "不要提及工具调用、检索过程、Agent、数据库、工作记忆等内部实现。"
                    + "不要输出 noteId、内部 ID、状态码、创建时间等技术元数据，除非用户明确询问。"
                    + "对重复、相似的信息先归纳，再回答。"
                    + "简单问题简短回答，复杂问题再展开。";
        }
    }
}
