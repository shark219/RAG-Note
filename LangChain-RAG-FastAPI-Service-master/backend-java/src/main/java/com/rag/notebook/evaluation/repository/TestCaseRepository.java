package com.rag.notebook.evaluation.repository;

import com.rag.notebook.evaluation.entity.TestCase;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    List<TestCase> findByUserIdOrderByCreatedAtDesc(String userId);

    List<TestCase> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    @Query("SELECT t FROM TestCase t WHERE t.userId = :userId AND t.question IN " +
           "(SELECT t2.question FROM TestCase t2 WHERE t2.userId = :userId GROUP BY t2.question HAVING COUNT(t2) > 1)")
    List<TestCase> findDuplicatesByUserId(@Param("userId") String userId);

    @Modifying
    @Query("DELETE FROM TestCase t WHERE t.id IN :ids")
    int deleteByIds(@Param("ids") List<Long> ids);
}
