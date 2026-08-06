package com.rag.notebook.skill.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@Entity
@Table(name = "skills")
public class Skill {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", length = 64) private String userId;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(length = 64) private String version;
    @Column(length = 120) private String author;
    @Column(length = 80) private String sourceType;
    @Column(length = 80) private String icon;
    @Column(name = "package_path", length = 500) private String packagePath;
    @Column(name = "entry_file", length = 120) private String entryFile;
    @Column(name = "content_hash", length = 128) private String contentHash;
    @Column(name = "enabled", nullable = false) private Boolean enabled = true;
    @Column(name = "priority", nullable = false) private Integer priority = 0;
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", columnDefinition = "JSON")
    private Map<String, Object> manifestJson = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "runtime_config", columnDefinition = "JSON")
    private Map<String, Object> runtimeConfig = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "script_metadata", columnDefinition = "JSON")
    private Map<String, Object> scriptMetadata = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_metadata", columnDefinition = "JSON")
    private Map<String, Object> resourceMetadata = new LinkedHashMap<>();
}
