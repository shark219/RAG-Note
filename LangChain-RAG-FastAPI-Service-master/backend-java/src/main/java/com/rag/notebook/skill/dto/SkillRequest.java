package com.rag.notebook.skill.dto;

import lombok.Data;

import java.util.Map;

@Data
public class SkillRequest {
    private String name;
    private String description;
    private String version;
    private String author;
    private String sourceType;
    private String icon;
    private String packagePath;
    private String entryFile;
    private String contentHash;
    private Map<String, Object> manifestJson;
    private Map<String, Object> runtimeConfig;
    private Map<String, Object> scriptMetadata;
    private Map<String, Object> resourceMetadata;
    private Boolean enabled = true;
    private Integer priority = 0;
}
