package com.rag.notebook.knowledge.repository;

import com.rag.notebook.knowledge.entity.KnowledgeDocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeDocumentChunkRepository extends JpaRepository<KnowledgeDocumentChunk, String> {

    List<KnowledgeDocumentChunk> findByDocumentIdOrderByChunkIndexAsc(String documentId);

    @Query("SELECT c FROM KnowledgeDocumentChunk c WHERE c.document.id = :documentId ORDER BY c.chunkIndex ASC")
    List<KnowledgeDocumentChunk> findByDocumentId(@Param("documentId") String documentId);

    @Query("SELECT c FROM KnowledgeDocumentChunk c JOIN c.document d WHERE d.userId = :userId AND d.filename = :filename ORDER BY c.chunkIndex ASC")
    List<KnowledgeDocumentChunk> findByUserIdAndFilename(@Param("userId") String userId, @Param("filename") String filename);

    @Query("SELECT COUNT(c) FROM KnowledgeDocumentChunk c WHERE c.document.id = :documentId")
    long countByDocumentId(@Param("documentId") String documentId);

    @Query("SELECT c FROM KnowledgeDocumentChunk c JOIN c.document d WHERE d.userId = :userId AND d.md5 = :md5 ORDER BY c.chunkIndex ASC")
    List<KnowledgeDocumentChunk> findByUserIdAndMd5(@Param("userId") String userId, @Param("md5") String md5);

    @Query("SELECT c FROM KnowledgeDocumentChunk c WHERE c.document.id = :documentId AND c.parentId = :parentId ORDER BY c.chunkIndex ASC")
    List<KnowledgeDocumentChunk> findByDocumentIdAndParentId(@Param("documentId") String documentId,
                                                             @Param("parentId") String parentId);

    @Query("SELECT c FROM KnowledgeDocumentChunk c WHERE c.document.id = :documentId AND c.sectionPath = :sectionPath ORDER BY c.chunkIndex ASC")
    List<KnowledgeDocumentChunk> findByDocumentIdAndSectionPath(@Param("documentId") String documentId,
                                                                @Param("sectionPath") String sectionPath);

    void deleteByDocumentId(String documentId);
}
