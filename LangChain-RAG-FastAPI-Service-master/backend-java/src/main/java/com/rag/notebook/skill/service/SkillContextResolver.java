package com.rag.notebook.skill.service;

import com.rag.notebook.skill.entity.Skill;
import com.rag.notebook.skill.repo.SkillRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SkillContextResolver {
    private final SkillRepository repository;
    private final SkillPackageService packageService;

    public SkillContextResolver(SkillRepository repository, SkillPackageService packageService) {
        this.repository = repository;
        this.packageService = packageService;
    }

    public Context resolve(String userId) {
        List<Skill> skills = repository.findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc(userId);
        StringBuilder prompt = new StringBuilder();
        Set<String> tools = new LinkedHashSet<>();
        boolean restricted = false;
        for (Skill skill : skills) {
            String instructions = packageService.readInstructions(skill);
            if (instructions != null && !instructions.isBlank()) {
                prompt.append("\n\n[Skill: ").append(skill.getName()).append("]\n").append(instructions);
            }
            Object allowedTools = skill.getRuntimeConfig().get("allowedTools");
            if (allowedTools instanceof List<?> names && !names.isEmpty()) {
                restricted = true;
                names.forEach(name -> tools.add(String.valueOf(name)));
            }
        }
        return new Context(prompt.toString(), tools, restricted, fingerprint(skills));
    }

    private String fingerprint(List<Skill> skills) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Skill skill : skills) {
                md.update((skill.getId() + ":" + skill.getVersion() + ":" + skill.getEnabled() + ":" + skill.getContentHash())
                        .getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "none";
        }
    }

    public record Context(String prompt, Set<String> allowedTools, boolean restricted, String version) {}
}
