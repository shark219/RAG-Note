package com.rag.notebook.skill.repo;

import com.rag.notebook.skill.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<Skill, Long> {
    List<Skill> findByUserIdIsNullOrUserIdOrderByPriorityDesc(String userId);
    List<Skill> findByEnabledTrueAndUserIdIsNullOrEnabledTrueAndUserIdOrderByPriorityDesc(String userId);
    List<Skill> findByUserIdOrderByPriorityDesc(String userId);
    Optional<Skill> findByNameAndUserId(String name, String userId);
}
