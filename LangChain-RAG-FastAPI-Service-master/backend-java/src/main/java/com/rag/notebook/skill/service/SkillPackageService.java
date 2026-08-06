package com.rag.notebook.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.skill.entity.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class SkillPackageService {
    private static final long MAX_PACKAGE_SIZE = 50L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2000;
    private static final long MAX_TOTAL_SIZE = 50L * 1024 * 1024;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "__pycache__", "node_modules");

    private final ObjectMapper objectMapper;
    private final SkillService skillService;
    private final Path storageRoot;

    public SkillPackageService(ObjectMapper objectMapper, SkillService skillService,
                               @Value("${app.skills.store-dir:data/skills}") String storeDir) {
        this.objectMapper = objectMapper;
        this.skillService = skillService;
        this.storageRoot = Path.of(storeDir).toAbsolutePath().normalize();
    }

    public Map<String, Object> importZip(String userId, MultipartFile file, String sourceType) {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "Skill压缩包不能为空");
        if (file.getSize() > MAX_PACKAGE_SIZE) throw new BusinessException(400, "Skill压缩包不能超过50MB");
        try {
            Files.createDirectories(storageRoot);
            Path temp = Files.createTempFile("skill-", ".zip");
            try {
                file.transferTo(temp);
                return importZipFile(userId, temp, sourceType);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new BusinessException(500, "Skill压缩包处理失败: " + e.getMessage());
        }
    }

    public Map<String, Object> importGit(String userId, String url, String branch) {
        if (url == null || url.isBlank()) throw new BusinessException(400, "Git仓库地址不能为空");
        if (!isAllowedRepoUrl(url)) throw new BusinessException(400, "仅允许GitHub或Gitee公开仓库");
        try {
            String zipUrl = buildRepoArchiveUrl(url, branch);
            Path temp = Files.createTempFile("skill-git-", ".zip");
            try {
                downloadToFile(zipUrl, temp);
                return importZipFile(userId, temp, "GIT");
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "Git导入失败: " + e.getMessage());
        }
    }

    public Map<String, Object> importStore(String userId, String baseUrl, String zipPath, String sha256) {
        if (baseUrl == null || baseUrl.isBlank()) throw new BusinessException(400, "Skill中心地址不能为空");
        if (!baseUrl.startsWith("https://")) throw new BusinessException(400, "Skill中心必须使用HTTPS");
        if (zipPath == null || zipPath.isBlank()) throw new BusinessException(400, "zipPath不能为空");
        try {
            String sourceUrl = baseUrl.endsWith("/") ? baseUrl + zipPath : baseUrl + "/" + zipPath;
            Path temp = Files.createTempFile("skill-store-", ".zip");
            try {
                downloadToFile(sourceUrl, temp);
                if (sha256 != null && !sha256.isBlank()) {
                    String actual = sha256(Files.readAllBytes(temp));
                    if (!actual.equalsIgnoreCase(sha256)) throw new BusinessException(400, "Skill包校验失败");
                }
                return importZipFile(userId, temp, "STORE");
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "Skill中心安装失败: " + e.getMessage());
        }
    }

    public Map<String, Object> loadStoreManifest(String url) {
        if (url == null || url.isBlank()) throw new BusinessException(400, "Skill中心源地址不能为空");
        if (!url.startsWith("https://")) throw new BusinessException(400, "Skill中心必须使用HTTPS");
        try {
            String manifestUrl = url.endsWith("/") ? url + "manifest.json" : url + "/manifest.json";
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(manifestUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new BusinessException(400, "Skill中心manifest拉取失败");
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "Skill中心manifest读取失败: " + e.getMessage());
        }
    }

    public String loadStoreReadme(String baseUrl, String readmePath) {
        if (baseUrl == null || baseUrl.isBlank()) throw new BusinessException(400, "Skill中心地址不能为空");
        if (readmePath == null || readmePath.isBlank()) throw new BusinessException(400, "readme路径不能为空");
        if (!baseUrl.startsWith("https://")) throw new BusinessException(400, "Skill中心必须使用HTTPS");
        try {
            String fileUrl = baseUrl.endsWith("/") ? baseUrl + readmePath : baseUrl + "/" + readmePath;
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(fileUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() >= 400) throw new BusinessException(400, "README 文件拉取失败");
            return response.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "README 文件读取失败: " + e.getMessage());
        }
    }

    public String readInstructions(Skill skill) {
        if (skill.getPackagePath() == null || skill.getEntryFile() == null) return "";
        Path root = Path.of(skill.getPackagePath()).toAbsolutePath().normalize();
        Path entry = root.resolve(skill.getEntryFile()).normalize();
        if (!entry.startsWith(root) || !Files.isRegularFile(entry)) return "";
        try {
            return Files.readString(entry);
        } catch (IOException e) {
            throw new BusinessException(500, "Skill内容读取失败");
        }
    }

    // ==================== Zip import (directory-based SKILL.md discovery) ====================

    private Map<String, Object> importZipFile(String userId, Path zip, String sourceType) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("skill-extract-");
            safeExtractZip(zip, tempDir);

            List<Path> skillDirs = findSkillDirs(tempDir);
            if (skillDirs.isEmpty()) throw new BusinessException(400, "zip 文件中未找到 SKILL.md");

            List<Skill> imported = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (Path skillDir : skillDirs) {
                try {
                    imported.add(createSkillFromDir(userId, skillDir, sourceType));
                } catch (BusinessException e) {
                    errors.add(e.getMessage());
                }
            }

            if (imported.isEmpty()) {
                String errMsg = errors.isEmpty() ? "zip 文件中未找到有效的 SKILL.md"
                        : "所有 Skills 导入失败: " + String.join("; ", errors);
                throw new BusinessException(400, errMsg);
            }

            if (!errors.isEmpty()) log.warn("部分 Skills 上传失败: {}", String.join("; ", errors));

            return Map.of("count", imported.size(), "skills", imported);
        } catch (IOException e) {
            throw new BusinessException(500, "Skill包处理失败: " + e.getMessage());
        } finally {
            if (tempDir != null) deleteTree(tempDir);
        }
    }

    /**
     * Extract zip to destDir with security checks (path traversal, zip bomb, symlinks).
     */
    private void safeExtractZip(Path zip, Path destDir) {
        try (InputStream input = Files.newInputStream(zip);
             ZipInputStream zis = new ZipInputStream(input)) {
            ZipEntry entry;
            int entryCount = 0;
            long totalSize = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++entryCount > MAX_ENTRIES) throw new BusinessException(400, "Skill包文件数量超过限制");

                totalSize += entry.getSize();
                if (totalSize > MAX_TOTAL_SIZE) throw new BusinessException(400, "Skill包解压后总大小超出限制");

                String name = entry.getName().replace('\\', '/');
                Path normalized = Path.of(name).normalize();
                if (normalized.isAbsolute() || normalized.startsWith("..") || name.contains("\0"))
                    throw new BusinessException(400, "Skill包包含非法路径");

                Path target = destDir.resolve(normalized).normalize();
                if (!target.startsWith(destDir))
                    throw new BusinessException(400, "Skill包包含非法路径");

                Files.createDirectories(target.getParent());
                Files.write(target, zis.readAllBytes());
            }
        } catch (IOException e) {
            throw new BusinessException(500, "Skill包解压失败: " + e.getMessage());
        }
    }

    /**
     * Walk the extracted directory tree to find all directories containing SKILL.md.
     * Once a SKILL.md is found, do not recurse into its subdirectories.
     */
    private List<Path> findSkillDirs(Path rootDir) throws IOException {
        List<Path> skillDirs = new ArrayList<>();
        findSkillDirsRecursive(rootDir, skillDirs);
        return skillDirs;
    }

    private void findSkillDirsRecursive(Path dir, List<Path> result) throws IOException {
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        if (SKIP_DIRS.contains(name)) return;
        if (Files.isRegularFile(dir.resolve("SKILL.md"))) {
            result.add(dir);
            return; // stop recursing into this skill's children
        }
        try (var stream = Files.list(dir)) {
            for (Path child : stream.sorted().toList()) {
                if (Files.isDirectory(child)) findSkillDirsRecursive(child, result);
            }
        }
    }

    /**
     * Create a Skill from a directory containing SKILL.md.
     * Parses YAML frontmatter, copies all files to permanent storage.
     */
    private Skill createSkillFromDir(String userId, Path skillDir, String sourceType) throws IOException {
        Path skillMdPath = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMdPath))
            throw new BusinessException(400, "SKILL.md 文件不存在");

        String content = Files.readString(skillMdPath);
        Map<String, Object> manifest = parseSkillMd(content);

        Path packageRoot = storageRoot.resolve(UUID.randomUUID().toString()).normalize();
        Files.createDirectories(packageRoot);
        copySkillFiles(skillDir, packageRoot);

        byte[] skillMdBytes = Files.readAllBytes(skillMdPath);
        String hash = sha256(skillMdBytes);

        return skillService.upsertImportedSkill(userId, manifest, packageRoot.toString(), sourceType, hash);
    }

    /**
     * Parse SKILL.md YAML frontmatter.
     * Expected format:
     * ---
     * name: skill-name
     * description: skill description
     * ---
     * Body content...
     */
    private Map<String, Object> parseSkillMd(String content) {
        if (content == null || !content.strip().startsWith("---"))
            throw new BusinessException(400, "SKILL.md 必须以 YAML frontmatter 开头 (---)");

        String[] parts = content.split("---", 3);
        if (parts.length < 3)
            throw new BusinessException(400, "SKILL.md 格式无效，缺少 YAML frontmatter 结束标记");

        Yaml yaml = new Yaml();
        Object parsed;
        try {
            parsed = yaml.load(parts[1]);
        } catch (Exception e) {
            throw new BusinessException(400, "YAML frontmatter 解析失败: " + e.getMessage());
        }

        if (!(parsed instanceof Map<?, ?> fm))
            throw new BusinessException(400, "YAML frontmatter 必须是对象 (mapping)");

        Map<String, Object> manifest = new LinkedHashMap<>();
        Object nameObj = fm.get("name");
        Object descObj = fm.get("description");
        if (!(nameObj instanceof String) || ((String) nameObj).isBlank())
            throw new BusinessException(400, "SKILL.md 缺少 name 字段");
        if (!(descObj instanceof String) || ((String) descObj).isBlank())
            throw new BusinessException(400, "SKILL.md 缺少 description 字段");

        manifest.put("name", ((String) nameObj).strip());
        manifest.put("description", ((String) descObj).strip());
        manifest.put("entry", "SKILL.md");

        // Copy optional fields from frontmatter
        for (String key : List.of("version", "author", "icon")) {
            Object val = fm.get(key);
            if (val instanceof String s && !s.isBlank()) manifest.put(key, s);
        }
        // Copy nested objects if present
        for (String key : List.of("runtime", "scripts", "resources")) {
            Object val = fm.get(key);
            if (val instanceof Map<?, ?> m) {
                manifest.put(key, new LinkedHashMap<>((Map<String, Object>) m));
            }
        }

        return manifest;
    }

    /**
     * Copy all files from skillDir to packageRoot, skipping ignored directories.
     */
    private void copySkillFiles(Path srcDir, Path dstDir) throws IOException {
        try (var stream = Files.walk(srcDir)) {
            for (Path src : stream.filter(Files::isRegularFile).toList()) {
                Path relative = srcDir.relativize(src);
                if (SKIP_DIRS.contains(relative.getName(0).toString())) continue;
                Path dst = dstDir.resolve(relative).normalize();
                if (!dst.startsWith(dstDir)) throw new BusinessException(400, "Skill包包含非法路径");
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst);
            }
        }
    }

    // ==================== Network helpers ====================

    private boolean isAllowedRepoUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            return host != null && (host.endsWith("github.com") || host.endsWith("gitee.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private String buildRepoArchiveUrl(String url, String branch) {
        URI uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("\\.git$", "");
        String archivePath = path.startsWith("/") ? path.substring(1) : path;
        String ref = (branch == null || branch.isBlank()) ? "main" : branch;
        if (uri.getHost() != null && uri.getHost().endsWith("github.com")) {
            return uri.getScheme() + "://" + uri.getHost() + "/" + archivePath + "/archive/refs/heads/" + ref + ".zip";
        }
        if (uri.getHost() != null && uri.getHost().endsWith("gitee.com")) {
            return uri.getScheme() + "://" + uri.getHost() + "/" + archivePath + "/repository/archive/" + ref + ".zip";
        }
        throw new BusinessException(400, "仅允许GitHub或Gitee公开仓库");
    }

    private void downloadToFile(String sourceUrl, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(sourceUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) throw new BusinessException(400, "远程资源下载失败");
        Files.write(target, response.body());
    }

    // ==================== Utility ====================

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("计算Skill包摘要失败", e);
        }
    }

    private void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Skill清理失败: {}", path);
                }
            });
        } catch (IOException e) {
            log.warn("Skill目录清理失败: {}", root);
        }
    }
}
