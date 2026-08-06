package com.rag.notebook.system.service;

import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.system.dto.LlmConfigRequest;
import com.rag.notebook.system.entity.LlmConfig;
import com.rag.notebook.system.repo.LlmConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LlmConfigService {

    private final LlmConfigRepository repository;

    public LlmConfigService(LlmConfigRepository repository) {
        this.repository = repository;
    }

    public List<LlmConfig> listAll() {
        return repository.findAll();
    }

    public LlmConfig getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("LLM配置不存在"));
    }

    public LlmConfig getActive() {
        return repository.findByIsActiveTrue().orElse(null);
    }

    @Transactional
    public LlmConfig create(LlmConfigRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException("配置名称不能为空");
        }
        if (req.getModel() == null || req.getModel().isBlank()) {
            throw new BusinessException("模型名称不能为空");
        }
        if (req.getApiUrl() == null || req.getApiUrl().isBlank()) {
            throw new BusinessException("API URL不能为空");
        }

        LlmConfig config = new LlmConfig();
        config.setName(req.getName());
        config.setProvider(req.getProvider() != null ? req.getProvider() : "openai-compatible");
        config.setModel(req.getModel());
        config.setActualModel(
                req.getActualModel() != null && !req.getActualModel().isBlank()
                        ? req.getActualModel()
                        : resolveActualModel(req.getProvider(), req.getModel()));
        config.setApiUrl(req.getApiUrl());
        config.setApiKey(req.getApiKey());
        boolean isFirst = repository.count() == 0;
        config.setIsActive(isFirst);

        return repository.save(config);
    }

    @Transactional
    public LlmConfig update(Long id, LlmConfigRequest req) {
        LlmConfig config = getById(id);

        if (req.getName() != null && !req.getName().isBlank()) {
            config.setName(req.getName());
        }
        if (req.getProvider() != null && !req.getProvider().isBlank()) {
            config.setProvider(req.getProvider());
        }
        if (req.getModel() != null && !req.getModel().isBlank()) {
            config.setModel(req.getModel());
        }
        if (req.getApiUrl() != null && !req.getApiUrl().isBlank()) {
            config.setApiUrl(req.getApiUrl());
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            config.setApiKey(req.getApiKey());
        }
        if (req.getActualModel() != null && !req.getActualModel().isBlank()) {
            config.setActualModel(req.getActualModel());
        } else {
            config.setActualModel(resolveActualModel(config.getProvider(), config.getModel()));
        }

        return repository.save(config);
    }

    @Transactional
    public void delete(Long id) {
        LlmConfig config = getById(id);
        if (Boolean.TRUE.equals(config.getIsActive())) {
            throw new BusinessException("不能删除当前激活的配置，请先激活其他配置");
        }
        repository.delete(config);
    }

    @Transactional
    public LlmConfig activate(Long id) {
        LlmConfig config = getById(id);
        // 取消所有激活
        repository.findByIsActiveTrue().ifPresent(active -> {
            active.setIsActive(false);
            repository.save(active);
        });
        config.setIsActive(true);
        return repository.save(config);
    }

    public Map<String, Object> testConnection(LlmConfigRequest req) {
        String apiUrl = req.getApiUrl();
        String apiKey = req.getApiKey();
        String model = req.getModel();

        if (apiKey == null || apiKey.isBlank()) {
            return result(false, "API Key不能为空");
        }

        try {
            String baseUrl = apiUrl.replaceAll("/+$", "");
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            // 尝试原 URL 的 /chat/completions
            HttpResponse<String> response = sendChatRequest(client, baseUrl, apiKey, model);
            if (isValidJsonResponse(response)) {
                return result(true, "连接成功");
            }

            // 如果返回了 HTML（常见于 New API / One API 等中转面板），尝试在 URL 后加 /v1
            if (isHtmlResponse(response) && !baseUrl.endsWith("/v1")) {
                String v1Url = baseUrl + "/v1";
                response = sendChatRequest(client, v1Url, apiKey, model);
                if (isValidJsonResponse(response)) {
                    return result(true, "连接成功（提示：API URL 建议改为 " + v1Url + "）");
                }
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    return result(false, "连接失败，建议将 API URL 改为 " + v1Url + " 后重试，当前状态码: " + response.statusCode());
                }
            }

            if (response.statusCode() == 429) {
                return result(false, "连接失败，接口被限流或被上游拦截，状态码: 429");
            }
            if (response.statusCode() == 400 && response.body() != null && response.body().contains("supported API model names")) {
                return result(false, "连接失败，模型名不匹配，请将模型名改为实际支持的名称");
            }

            return result(false, "连接失败，状态码: " + response.statusCode() + "，响应: " + trimBody(response.body()));
        } catch (Exception e) {
            log.warn("Test connection failed: {}", e.getMessage());
            return result(false, "连接异常: " + e.getMessage());
        }
    }

    private boolean isValidJsonResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) return false;
        String body = response.body();
        if (body == null || body.isBlank()) return false;
        String trimmed = body.trim();
        return (trimmed.startsWith("{") || trimmed.startsWith("["))
                && !trimmed.startsWith("<!") && !trimmed.startsWith("<html");
    }

    private boolean isHtmlResponse(HttpResponse<String> response) {
        String ct = response.headers().firstValue("content-type").orElse("");
        if (ct.contains("text/html")) return true;
        String body = response.body();
        return body != null && body.trim().startsWith("<!");
    }

    private HttpResponse<String> sendModelsRequest(HttpClient client, String baseUrl, String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendChatRequest(HttpClient client, String baseUrl, String apiKey, String model) throws Exception {
        String body = "{\"model\":\"" + (model != null ? model : "gpt-3.5-turbo") +
                "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> result(boolean success, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", success);
        data.put("message", message);
        return data;
    }

    private String trimBody(String body) {
        if (body == null) {
            return "";
        }
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned;
    }


    public Map<String, Object> testById(Long id) {
        LlmConfig config = getById(id);
        if (config.getActualModel() == null || config.getActualModel().isBlank()) {
            config.setActualModel(resolveActualModel(config.getProvider(), config.getModel()));
        }
        LlmConfigRequest req = new LlmConfigRequest();
        req.setApiUrl(config.getApiUrl());
        req.setApiKey(config.getApiKey());
        req.setModel(config.getActualModel());
        return testConnection(req);
    }

    private String resolveActualModel(String provider, String model) {
        if (model == null || model.isBlank()) {
            return model;
        }
        if (provider == null) {
            return model;
        }
        if ("deepseek".equalsIgnoreCase(provider)) {
            if ("deepseek-flash".equalsIgnoreCase(model)) {
                return "deepseek-v4-flash";
            }
            if ("deepseek-pro".equalsIgnoreCase(model)) {
                return "deepseek-v4-pro";
            }
        }
        return model;
    }
}
