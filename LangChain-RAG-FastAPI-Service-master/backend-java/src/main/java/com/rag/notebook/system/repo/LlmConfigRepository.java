package com.rag.notebook.system.repo;

import com.rag.notebook.system.entity.LlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmConfigRepository extends JpaRepository<LlmConfig, Long> {

    Optional<LlmConfig> findByIsActiveTrue();
}
