package com.rag.notebook.skill.service;

import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.skill.dto.SkillRequest;
import com.rag.notebook.skill.entity.Skill;
import com.rag.notebook.skill.repo.SkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class SkillService {
    private final SkillRepository repository;
    public SkillService(SkillRepository repository) { this.repository = repository; }
    public List<Skill> list(String userId) { return repository.findByUserIdIsNullOrUserIdOrderByPriorityDesc(userId); }
    public Skill get(Long id, String userId) { return repository.findById(id).filter(s -> s.getUserId() == null || s.getUserId().equals(userId)).orElseThrow(() -> new BusinessException("Skill不存在")); }
    @Transactional public Skill create(SkillRequest req, String userId) { Skill s = new Skill(); apply(s, req); s.setUserId(userId); return repository.save(s); }
    @Transactional public Skill update(Long id, SkillRequest req, String userId) { Skill s = get(id, userId); apply(s, req); return repository.save(s); }
    @Transactional public Skill setEnabled(Long id, boolean enabled, String userId) { Skill s = get(id, userId); s.setEnabled(enabled); return repository.save(s); }
    @Transactional public void delete(Long id, String userId) { repository.delete(get(id, userId)); }
    public Skill upsertImportedSkill(String userId, Map<String, Object> manifest, String packagePath, String sourceType, String contentHash) {
        String name = valueAsString(manifest.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException("Skill名称不能为空");
        Skill skill = repository.findByNameAndUserId(name, userId).orElseGet(() -> {
            Skill s = new Skill();
            s.setUserId(userId);
            s.setName(name);
            return s;
        });
        skill.setDescription(valueAsString(manifest.get("description")));
        skill.setVersion(valueAsString(manifest.get("version")));
        skill.setAuthor(valueAsString(manifest.get("author")));
        skill.setSourceType(sourceType);
        skill.setIcon(valueAsString(manifest.get("icon")));
        skill.setPackagePath(packagePath);
        skill.setEntryFile(valueAsString(manifest.getOrDefault("entry", "SKILL.md")));
        skill.setContentHash(contentHash);
        skill.setManifestJson(manifest);
        skill.setEnabled(true);
        Object runtime = manifest.get("runtime");
        if (runtime instanceof Map<?, ?> runtimeMap) {
            skill.setRuntimeConfig((Map<String, Object>) runtimeMap);
        }
        Object scripts = manifest.get("scripts");
        if (scripts instanceof Map<?, ?> scriptsMap) {
            skill.setScriptMetadata((Map<String, Object>) scriptsMap);
        }
        Object resources = manifest.get("resources");
        if (resources instanceof Map<?, ?> resourcesMap) {
            skill.setResourceMetadata((Map<String, Object>) resourcesMap);
        }
        return repository.save(skill);
    }
    private void apply(Skill s, SkillRequest r) {
        if (r.getName() == null || r.getName().isBlank()) throw new BusinessException("Skill名称不能为空");
        s.setName(r.getName());
        s.setDescription(r.getDescription());
        s.setEnabled(r.getEnabled() == null || r.getEnabled());
        s.setPriority(r.getPriority() == null ? 0 : r.getPriority());
        s.setVersion(r.getVersion());
        s.setAuthor(r.getAuthor());
        s.setSourceType(r.getSourceType());
        s.setIcon(r.getIcon());
        s.setEntryFile(r.getEntryFile());
        s.setManifestJson(r.getManifestJson() == null ? Map.of() : r.getManifestJson());
        s.setRuntimeConfig(r.getRuntimeConfig() == null ? Map.of() : r.getRuntimeConfig());
        s.setScriptMetadata(r.getScriptMetadata() == null ? Map.of() : r.getScriptMetadata());
        s.setResourceMetadata(r.getResourceMetadata() == null ? Map.of() : r.getResourceMetadata());
    }
    private String valueAsString(Object value) { return value == null ? null : String.valueOf(value); }
}
