package com.rag.notebook.skill;

import com.rag.notebook.skill.entity.Skill;
import com.rag.notebook.skill.repo.SkillRepository;
import com.rag.notebook.skill.service.SkillContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillContextResolverTest {

    @Mock private SkillRepository repository;
    private SkillContextResolver resolver;

    private Skill buildSkill(Long id, String name, String prompt, List<String> tools, int priority, int version) {
        Skill s = new Skill();
        s.setId(id);
        s.setName(name);
        s.setPromptFragment(prompt);
        s.setToolNames(tools);
        s.setEnabled(true);
        s.setPriority(priority);
        s.setVersion(version);
        return s;
    }

    @BeforeEach
    void setUp() {
        resolver = new SkillContextResolver(repository);
    }

    @Test
    void resolveReturnsEmptyContextWhenNoSkills() {
        when(repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc("user-1"))
                .thenReturn(List.of());
        SkillContextResolver.Context ctx = resolver.resolve("user-1");
        assertTrue(ctx.prompt().isBlank());
        assertTrue(ctx.allowedTools().isEmpty());
        assertFalse(ctx.restricted());
    }

    @Test
    void resolveAssemblesPromptFromEnabledSkills() {
        Skill java = buildSkill(1L, "Java", "使用 Java 术语", List.of(), 10, 1);
        Skill linux = buildSkill(2L, "Linux", "使用 Linux 术语", List.of(), 5, 1);
        when(repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc("user-1"))
                .thenReturn(List.of(java, linux));

        SkillContextResolver.Context ctx = resolver.resolve("user-1");
        assertTrue(ctx.prompt().contains("[Skill: Java]"));
        assertTrue(ctx.prompt().contains("[Skill: Linux]"));
    }

    @Test
    void resolveBuildsToolWhitelistAndRestrictedFlag() {
        Skill skill = buildSkill(1L, "Java", "", List.of("ragSummary", "searchNotes"), 10, 1);
        when(repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc("user-1"))
                .thenReturn(List.of(skill));

        SkillContextResolver.Context ctx = resolver.resolve("user-1");
        assertTrue(ctx.restricted());
        assertTrue(ctx.allowedTools().contains("ragSummary"));
        assertTrue(ctx.allowedTools().contains("searchNotes"));
    }

    @Test
    void resolveNotRestrictedWhenNoSkillHasTools() {
        Skill java = buildSkill(1L, "Java", "Java 提示词", List.of(), 10, 1);
        when(repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc("user-1"))
                .thenReturn(List.of(java));

        SkillContextResolver.Context ctx = resolver.resolve("user-1");
        assertFalse(ctx.restricted());
        assertTrue(ctx.allowedTools().isEmpty());
    }

    @Test
    void resolveVersionChangesWhenSkillVersionIncrements() {
        Skill skillV1 = buildSkill(1L, "Test", "prompt", List.of("tool"), 10, 1);
        when(repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc("user-1"))
                .thenReturn(List.of(skillV1));
        String version1 = resolver.resolve("user-1").version();

        skillV1.setVersion(2);
        when(repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc("user-1"))
                .thenReturn(List.of(skillV1));
        String version2 = resolver.resolve("user-1").version();

        assertNotEquals(version1, version2);
    }

    @Test
    void resolveSkipsDisabledSkills() {
        Skill enabled = buildSkill(1L, "Java", "Java 提示词", List.of(), 10, 1);
        Skill disabled = buildSkill(2L, "Disabled", "不可见", List.of(), 100, 1);
        disabled.setEnabled(false);
        when(repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc("user-1"))
                .thenReturn(List.of(enabled)); // disabled skill not in result

        SkillContextResolver.Context ctx = resolver.resolve("user-1");
        assertFalse(ctx.prompt().contains("Disabled"));
        assertFalse(ctx.prompt().contains("不可见"));
    }
}
