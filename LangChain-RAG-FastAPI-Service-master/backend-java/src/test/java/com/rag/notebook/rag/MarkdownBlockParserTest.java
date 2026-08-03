package com.rag.notebook.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownBlockParserTest {

    @Test
    void skipsStandaloneHorizontalRuleVariants() {
        String paragraph1 = "这是一段用于测试分隔线过滤逻辑的正文内容，长度超过五十个字符以避免被误判为独立标题。";
        String paragraph2 = "这是紧跟在多种分隔线之后的第二段正文内容，同样长度超过五十个字符以避免被误判为独立标题。";
        String text = paragraph1 + "\n\n---\n\n***\n\n___\n\n- - -\n\n" + paragraph2;

        List<DocumentBlock> blocks = MarkdownBlockParser.parse(text);

        assertFalse(blocks.stream().anyMatch(b -> b.markdown().strip().equals("---")
                || b.markdown().strip().equals("***")
                || b.markdown().strip().equals("___")
                || b.markdown().strip().equals("- - -")));
        assertEquals(2, blocks.size());
        assertEquals(paragraph1, blocks.get(0).markdown());
        assertEquals(paragraph2, blocks.get(1).markdown());
    }

    @Test
    void doesNotSkipTableSeparatorRow() {
        String text = "| 列1 | 列2 |\n|---|---|\n| a | b |";

        List<DocumentBlock> blocks = MarkdownBlockParser.parse(text);

        assertEquals(1, blocks.size());
        assertEquals(DocumentBlock.Type.TABLE, blocks.get(0).type());
        assertTrue(blocks.get(0).markdown().contains("---"));
    }

    @Test
    void doesNotSkipHorizontalRuleInsideFencedCodeBlock() {
        String text = "```yaml\nkey: value\n---\nother: value\n```";

        List<DocumentBlock> blocks = MarkdownBlockParser.parse(text);

        assertEquals(1, blocks.size());
        assertEquals(DocumentBlock.Type.CODE, blocks.get(0).type());
        assertTrue(blocks.get(0).markdown().contains("---"));
    }

    @Test
    void dashDashDashLineIsNotMisreadAsListItem() {
        String text = "- - -\n\n- 真实列表项一\n- 真实列表项二";

        List<DocumentBlock> blocks = MarkdownBlockParser.parse(text);

        assertEquals(1, blocks.size());
        assertEquals(DocumentBlock.Type.LIST, blocks.get(0).type());
        assertFalse(blocks.get(0).markdown().contains("- - -"));
    }
}
