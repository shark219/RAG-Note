package com.rag.notebook.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagChunkerTest {

    @Test
    void noteChunksNeverContainOnlyASeparatorLine() {
        StringBuilder content = new StringBuilder();
        content.append("# 一、类中位置\n");
        content.append("成员变量的位置说明文字，需要足够长以触发多段切分逻辑。".repeat(10)).append("\n");
        content.append("---\n");
        content.append("# 二、修饰符\n");
        content.append("局部变量的修饰符说明文字，同样需要足够长以确保跨越章节边界。".repeat(10)).append("\n");
        content.append("***\n");
        content.append("# 三、初始化要求\n");
        content.append("初始化要求说明文字，继续填充内容以覆盖多个 chunk 边界情况。".repeat(10));

        List<RagChunk> chunks = RagChunker.splitNote("note-1", "成员变量与局部变量的区别总结",
                content.toString(), 200, 20);

        assertFalse(chunks.isEmpty());
        for (RagChunk chunk : chunks) {
            String trimmed = chunk.getContent().strip();
            assertFalse(trimmed.equals("---") || trimmed.equals("***"),
                    "chunk 不应仅包含分隔线: " + trimmed);
        }
    }

    @Test
    void tableCellsContainingCodeKeywordsAreNotMisclassifiedAsCode() {
        StringBuilder content = new StringBuilder();
        content.append("# 对比\n");
        content.append("| 对比维度 | 知识库 | 笔记 |\n");
        content.append("|---------|-------------------|------|\n");
        content.append("| 成员变量 | 未提及 | 可被public、private、static等修饰符修饰 |\n");
        content.append("| 局部变量 | 未提及 | 不能被访问控制符修饰，但可被final修饰 |\n");
        content.append("补充说明文字，用于确保内容超过短文本阈值。".repeat(20));

        List<RagChunk> chunks = RagChunker.splitNote("note-2", "标题",
                content.toString(), 200, 20);

        boolean foundTableChunk = chunks.stream()
                .anyMatch(c -> c.getContent().contains("| 成员变量 |"));
        assertTrue(foundTableChunk, "应该存在包含表格内容的 chunk");
        chunks.stream()
                .filter(c -> c.getContent().contains("| 成员变量 |"))
                .forEach(c -> assertTrue("table".equals(c.getContentType()),
                        "表格 chunk 的 contentType 应为 table，实际为: " + c.getContentType()));
    }

    @Test
    void oversizedKnowledgeTableIsSplitByLineNotByCharacter() {
        StringBuilder table = new StringBuilder();
        table.append("| COMMAND | PID | USER | FD | TYPE | DEVICE | SIZE/OFF | NODE | NAME |\n");
        table.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < 40; i++) {
            table.append("| nginx | 929").append(i)
                    .append(" | root | 6u | IPv4 | 15249").append(i)
                    .append(" | 0t0 | TCP | LISTEN |\n");
        }

        List<RagChunk> chunks = RagChunker.splitKnowledge(table.toString(), 200, 20, false);

        assertFalse(chunks.isEmpty());
        for (RagChunk chunk : chunks) {
            for (String line : chunk.getContent().split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                assertTrue(trimmed.startsWith("|") && trimmed.endsWith("|"),
                        "表格行不应被从单元格中间切断: [" + trimmed + "]");
            }
        }
    }

    @Test
    void overlapDoesNotGlueLinesTogetherAcrossChunks() {
        StringBuilder table = new StringBuilder();
        table.append("| COMMAND | PID | USER |\n");
        table.append("| --- | --- | --- |\n");
        // 每行 3 列表格恰好有 4 个 "|"；行数足够多确保表格总长度超过 knowledgeKeepTogetherLimit（1200 字符），
        // 才会真正走到 splitLargeKnowledgeBlockByLine 的多 chunk 切分路径。
        for (int i = 0; i < 80; i++) {
            table.append("| nginx | 929").append(i).append(" | root |\n");
        }

        List<RagChunk> chunks = RagChunker.splitKnowledge(table.toString(), 120, 30, false);

        assertTrue(chunks.size() > 1, "应该产生多个 chunk 才能验证重叠部分不粘连");
        for (RagChunk chunk : chunks) {
            for (String line : chunk.getContent().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                long pipeCount = line.chars().filter(ch -> ch == '|').count();
                assertEquals(4, pipeCount, "每行应恰好有 4 个竖线分隔符，出现粘连会导致数量异常: [" + line + "]");
                assertTrue(line.strip().startsWith("|") && line.strip().endsWith("|"),
                        "每行应以完整的表格行开始和结束，不应被从中间切断: [" + line + "]");
            }
        }
    }
}
