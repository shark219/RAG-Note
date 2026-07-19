package com.rag.notebook.knowledge.repository;

import com.rag.notebook.knowledge.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {

    List<KnowledgeDocument> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<KnowledgeDocument> findByUserIdAndMd5(String userId, String md5);

    Optional<KnowledgeDocument> findByUserIdAndFilename(String userId, String filename);

    boolean existsByUserIdAndMd5(String userId, String md5);

    @Query("SELECT d FROM KnowledgeDocument d WHERE d.userId = :userId AND d.filename LIKE %:keyword%")
    List<KnowledgeDocument> searchByFilename(@Param("userId") String userId, @Param("keyword") String keyword);

    long countByUserId(String userId);
}
