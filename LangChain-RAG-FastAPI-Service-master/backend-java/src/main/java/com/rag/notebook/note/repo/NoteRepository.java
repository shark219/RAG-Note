package com.rag.notebook.note.repo;

import com.rag.notebook.note.entity.Note;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, String> {

    Page<Note> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    Page<Note> findByUserIdAndCategoryOrderByUpdatedAtDesc(String userId, String category, Pageable pageable);

    @Query("SELECT n FROM Note n WHERE n.userId = :userId ORDER BY n.updatedAt DESC")
    List<Note> findAllByUserId(@Param("userId") String userId);

    @Query("SELECT n.category, COUNT(n) FROM Note n WHERE n.userId = :userId GROUP BY n.category")
    List<Object[]> countByCategoryGrouped(@Param("userId") String userId);

    @Query("SELECT COUNT(n) FROM Note n WHERE n.userId = :userId AND n.category IS NULL")
    long countUncategorized(@Param("userId") String userId);

    long countByUserId(String userId);

    @Query("SELECT n FROM Note n WHERE n.userId = :userId AND (n.title LIKE %:keyword% OR n.content LIKE %:keyword%) ORDER BY n.updatedAt DESC")
    List<Note> searchByKeyword(@Param("userId") String userId, @Param("keyword") String keyword);
}
