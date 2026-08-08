package com.rag.notebook.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 测试
 */
@SpringBootTest
class ToolRegistryTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void testGetTool_Exists() {
        Optional<ToolDefinition> tool = toolRegistry.get("listNotes");
        assertTrue(tool.isPresent(), "listNotes should be registered");
        assertEquals("listNotes", tool.get().getName());
        assertEquals(ToolCategory.READ, tool.get().getCategory());
        assertEquals(RiskLevel.LOW, tool.get().getRiskLevel());
    }

    @Test
    void testGetTool_NotExists() {
        Optional<ToolDefinition> tool = toolRegistry.get("nonExistentTool");
        assertFalse(tool.isPresent(), "Non-existent tool should return empty");
    }

    @Test
    void testGetAllTools() {
        List<ToolDefinition> tools = toolRegistry.getAll();
        assertTrue(tools.size() > 10, "Should have multiple default tools");
    }

    @Test
    void testGetByCategory_Read() {
        List<ToolDefinition> readTools = toolRegistry.getByCategory(ToolCategory.READ);
        assertFalse(readTools.isEmpty(), "Should have READ category tools");
        assertTrue(readTools.stream().anyMatch(t -> t.getName().equals("listNotes")));
        assertTrue(readTools.stream().anyMatch(t -> t.getName().equals("getNote")));
    }

    @Test
    void testGetByCategory_Write() {
        List<ToolDefinition> writeTools = toolRegistry.getByCategory(ToolCategory.WRITE);
        assertFalse(writeTools.isEmpty(), "Should have WRITE category tools");
        assertTrue(writeTools.stream().anyMatch(t -> t.getName().equals("createNote")));
        assertTrue(writeTools.stream().anyMatch(t -> t.getName().equals("deleteNote")));
    }

    @Test
    void testGetByCategory_External() {
        List<ToolDefinition> externalTools = toolRegistry.getByCategory(ToolCategory.EXTERNAL);
        assertEquals(1, externalTools.size(), "Should have 1 EXTERNAL tool");
        assertEquals("fetchUrl", externalTools.get(0).getName());
    }

    @Test
    void testGetByRiskLevel_Low() {
        List<ToolDefinition> lowRiskTools = toolRegistry.getByRiskLevel(RiskLevel.LOW);
        assertFalse(lowRiskTools.isEmpty(), "Should have LOW risk tools");
        assertTrue(lowRiskTools.stream().anyMatch(t -> t.getName().equals("listNotes")));
    }

    @Test
    void testGetByRiskLevel_High() {
        List<ToolDefinition> highRiskTools = toolRegistry.getByRiskLevel(RiskLevel.HIGH);
        assertFalse(highRiskTools.isEmpty(), "Should have HIGH risk tools");
        assertTrue(highRiskTools.stream().anyMatch(t -> t.getName().equals("deleteNote")));
        assertTrue(highRiskTools.stream().anyMatch(t -> t.getName().equals("fetchUrl")));
    }

    @Test
    void testExists() {
        assertTrue(toolRegistry.exists("listNotes"));
        assertTrue(toolRegistry.exists("createNote"));
        assertTrue(toolRegistry.exists("fetchUrl"));
        assertFalse(toolRegistry.exists("nonExistentTool"));
    }

    @Test
    void testRegisterCustomTool() {
        ToolDefinition customTool = ToolDefinition.builder()
                .name("customTool")
                .description("Custom test tool")
                .category(ToolCategory.ANALYSIS)
                .riskLevel(RiskLevel.MEDIUM)
                .timeoutSeconds(15)
                .requiresApproval(false)
                .build();

        toolRegistry.register(customTool);

        Optional<ToolDefinition> retrieved = toolRegistry.get("customTool");
        assertTrue(retrieved.isPresent());
        assertEquals("customTool", retrieved.get().getName());
        assertEquals(ToolCategory.ANALYSIS, retrieved.get().getCategory());
    }

    @Test
    void testDeleteNoteRequiresApproval() {
        Optional<ToolDefinition> tool = toolRegistry.get("deleteNote");
        assertTrue(tool.isPresent());
        assertTrue(tool.get().isRequiresApproval(), "deleteNote should require approval");
    }

    @Test
    void testFetchUrlHasAllowedDomains() {
        Optional<ToolDefinition> tool = toolRegistry.get("fetchUrl");
        assertTrue(tool.isPresent());
        assertNotNull(tool.get().getAllowedDomains());
        assertFalse(tool.get().getAllowedDomains().isEmpty());
        assertTrue(tool.get().getAllowedDomains().contains("*.github.com"));
    }

    @Test
    void testToolTimeouts() {
        Optional<ToolDefinition> listNotes = toolRegistry.get("listNotes");
        assertTrue(listNotes.isPresent());
        assertEquals(10, listNotes.get().getTimeoutSeconds());

        Optional<ToolDefinition> fetchUrl = toolRegistry.get("fetchUrl");
        assertTrue(fetchUrl.isPresent());
        assertEquals(30, fetchUrl.get().getTimeoutSeconds(), "fetchUrl should have longer timeout");
    }
}
