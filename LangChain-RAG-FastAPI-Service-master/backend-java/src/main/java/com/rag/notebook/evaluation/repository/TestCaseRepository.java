package com.rag.notebook.evaluation.repository;

import com.rag.notebook.evaluation.entity.TestCase;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    List<TestCase> findByUserIdOrderByCreatedAtDesc(String userId);

    List<TestCase> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
