package com.rag.notebook.skill.controller;

import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.skill.dto.SkillRequest;
import com.rag.notebook.skill.entity.Skill;
import com.rag.notebook.skill.service.SkillPackageService;
import com.rag.notebook.skill.service.SkillService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/skills")
public class SkillController {
    private final SkillService service;
    private final SkillPackageService packageService;
    private final ApplicationProperties applicationProperties;

    public SkillController(SkillService service, SkillPackageService packageService,
                           ApplicationProperties applicationProperties) {
        this.service = service;
        this.packageService = packageService;
        this.applicationProperties = applicationProperties;
    }

    @GetMapping
    public ApiResponse<List<Skill>> list(@UserId String userId) {
        return ApiResponse.success(service.list(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Skill> get(@PathVariable Long id, @UserId String userId) {
        return ApiResponse.success(service.get(id, userId));
    }

    @PostMapping
    public ApiResponse<Skill> create(@RequestBody SkillRequest req, @UserId String userId) {
        return ApiResponse.success(service.create(req, userId));
    }

    @PutMapping("/{id}")
    public ApiResponse<Skill> update(@PathVariable Long id, @RequestBody SkillRequest req, @UserId String userId) {
        return ApiResponse.success(service.update(id, req, userId));
    }

    @PutMapping("/{id}/enable")
    public ApiResponse<Skill> enable(@PathVariable Long id, @UserId String userId) {
        return ApiResponse.success(service.setEnabled(id, true, userId));
    }

    @PutMapping("/{id}/disable")
    public ApiResponse<Skill> disable(@PathVariable Long id, @UserId String userId) {
        return ApiResponse.success(service.setEnabled(id, false, userId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @UserId String userId) {
        service.delete(id, userId);
        return ApiResponse.success("删除成功");
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> upload(@UserId String userId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(packageService.importZip(userId, file, "UPLOAD"));
    }

    @PostMapping("/import-git")
    public ApiResponse<Map<String, Object>> importGit(@UserId String userId, @RequestBody Map<String, Object> request) {
        String url = request.get("url") == null ? null : String.valueOf(request.get("url"));
        String branch = request.get("branch") == null ? "main" : String.valueOf(request.get("branch"));
        return ApiResponse.success(packageService.importGit(userId, url, branch));
    }

    @GetMapping("/{id}/content")
    public ApiResponse<Map<String, Object>> content(@PathVariable Long id, @UserId String userId) {
        Skill skill = service.get(id, userId);
        return ApiResponse.success(Map.of(
                "name", skill.getName(),
                "description", skill.getDescription() == null ? "" : skill.getDescription(),
                "entryFile", skill.getEntryFile() == null ? "SKILL.md" : skill.getEntryFile(),
                "manifest", skill.getManifestJson(),
                "runtime", skill.getRuntimeConfig(),
                "scripts", skill.getScriptMetadata(),
                "resources", skill.getResourceMetadata(),
                "content", packageService.readInstructions(skill)
        ));
    }

    @GetMapping("/store/manifest")
    public ApiResponse<Map<String, Object>> storeManifest(@RequestParam String url) {
        return ApiResponse.success(packageService.loadStoreManifest(url));
    }

    @PostMapping("/store/import")
    public ApiResponse<Map<String, Object>> storeImport(@UserId String userId, @RequestBody Map<String, Object> request) {
        String baseUrl = request.get("baseUrl") == null ? null : String.valueOf(request.get("baseUrl"));
        String zipPath = request.get("zipPath") == null ? null : String.valueOf(request.get("zipPath"));
        String sha256 = request.get("sha256") == null ? null : String.valueOf(request.get("sha256"));
        return ApiResponse.success(packageService.importStore(userId, baseUrl, zipPath, sha256));
    }

    @GetMapping("/store/config")
    public ApiResponse<Map<String, Object>> storeConfig() {
        return ApiResponse.success(Map.of(
                "name", applicationProperties.getSkills().getStore().getName(),
                "baseUrl", applicationProperties.getSkills().getStore().getBaseUrl()
        ));
    }

    @GetMapping("/store/readme")
    public ApiResponse<String> storeReadme(@RequestParam String url, @RequestParam String path) {
        return ApiResponse.success(packageService.loadStoreReadme(url, path));
    }
}
