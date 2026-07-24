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
 * 最终回答生成器——基于 Agent 的完整状态（目标 + 证据 + 产物）生成自然回答。
 *
 * 与旧版的关键区别：
 *   旧：只看"检索证据"（notes、KB summary），产物类工具结果被忽略 → "没有找到相关内容"
 *   新：同时看证据和产物，当产物已生成时，正确告知用户"已生成XXX"
 */
@Slf4j
@Service
public class ResponseComposer {

    private final ModelFactory modelFactory;

    public ResponseComposer(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 根据 Agent 状态生成最终回答。
     */
    public String compose(EvidencePack pack, AgentLoopResult.Outcome outcome) {
        String systemPrompt = loadComposePrompt();
        String userMessage = buildUserMessage(pack, outcome);

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

        log.info("Composer: 生成回答 {} 字, 证据笔记 {} 篇, 产物 {} 个",
                answer.length(), pack.notes().size(),
                pack.artifacts() != null ? pack.artifacts().size() : 0);
        return answer.trim();
    }

    private String buildUserMessage(EvidencePack pack, AgentLoopResult.Outcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(pack.userQuery()).append("\n\n");

        // 目标（如果有）
        if (pack.goal() != null && !pack.goal().isBlank()) {
            sb.append("任务目标：").append(pack.goal()).append("\n\n");
        }

        // 产物（最重要的新增部分）
        if (pack.hasArtifacts()) {
            sb.append("【已生成的产物】\n");
            for (Artifact a : pack.artifacts()) {
                sb.append("- ").append(a.label() != null ? a.label() : a.type());
                if (a.id() != null) sb.append(" (ID: ").append(a.id()).append(")");
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 检索证据
        String evidenceBlock = buildEvidenceBlock(pack);
        if (!evidenceBlock.isBlank()) {
            sb.append(evidenceBlock);
            sb.append("\n请根据以上信息回答用户的问题。");
        } else if (pack.hasArtifacts()) {
            // 有产物但没有检索证据——正常情况（如生成思维导图后无笔记内容）
            sb.append("请告知用户产物已生成，无需编造不存在的笔记内容。");
        } else if (pack.writeConfirmation() != null) {
            sb.append("请告知用户操作已完成。");
        } else {
            sb.append("未获取到任何相关数据。请诚实地告诉用户没有找到相关内容。");
        }

        if (outcome == AgentLoopResult.Outcome.MAX_ROUNDS) {
            sb.append("\n\n注意：本次检索达到了最大执行轮次，证据可能不完整。如果信息不足，请诚实说明。");
        }

        return sb.toString();
    }

    private String buildEvidenceBlock(EvidencePack pack) {
        StringBuilder sb = new StringBuilder();

        // 笔记证据
        if (!pack.notes().isEmpty()) {
            sb.append("【已获取的笔记内容】\n\n");
            for (EvidencePack.NoteEvidence note : pack.notes()) {
                sb.append("---\n");
                sb.append("标题：").append(note.title() != null ? note.title() : "无标题").append("\n");
                if (note.content() != null && !note.content().isBlank()) {
                    String label = switch (note.depth()) {
                        case CONTENT_EVIDENCE -> "完整内容";
                        case SEARCH_EVIDENCE -> "内容预览";
                        case LIST_EVIDENCE -> "内容摘要";
                        default -> "内容";
                    };
                    sb.append(label).append("：\n").append(note.content()).append("\n");
                }
                sb.append("\n");
            }
        }

        if (pack.knowledgeBaseSummary() != null) {
            sb.append("【知识库检索结果】\n").append(pack.knowledgeBaseSummary()).append("\n\n");
        }

        if (pack.noteStats() != null) {
            sb.append("【笔记统计】\n").append(pack.noteStats()).append("\n");
        }

        if (pack.todayReviews() != null) {
            sb.append("【今日复习】\n").append(pack.todayReviews()).append("\n");
        }

        if (pack.writeConfirmation() != null) {
            sb.append("【操作结果】\n").append(pack.writeConfirmation()).append("\n");
        }

        return sb.toString();
    }

    private String fallbackAnswer(EvidencePack pack) {
        if (pack.hasArtifacts()) {
            StringBuilder sb = new StringBuilder();
            sb.append("已生成以下产物：\n");
            for (Artifact a : pack.artifacts()) {
                sb.append("- ").append(a.label() != null ? a.label() : a.type()).append("\n");
            }
            return sb.toString().trim();
        }
        if (pack.writeConfirmation() != null) {
            return pack.writeConfirmation();
        }
        if (pack.isEmpty()) {
            return "抱歉，我暂时无法处理你的请求。请稍后再试。";
        }
        // 简单拼接兜底
        StringBuilder sb = new StringBuilder();
        for (EvidencePack.NoteEvidence note : pack.notes()) {
            if (note.title() != null) sb.append("### ").append(note.title()).append("\n\n");
            if (note.content() != null) sb.append(note.content()).append("\n\n");
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
            return "你是最终回答生成器。根据已获得的信息直接自然地回答用户。"
                    + "当看到【已生成的产物】中有内容时，告诉用户产物已生成。"
                    + "不要提及工具调用、检索过程、Agent、数据库等内部实现。"
                    + "不要输出 noteId、内部 ID、状态码等技术元数据。"
                    + "简单问题简短回答，复杂问题再展开。";
        }
    }
}
