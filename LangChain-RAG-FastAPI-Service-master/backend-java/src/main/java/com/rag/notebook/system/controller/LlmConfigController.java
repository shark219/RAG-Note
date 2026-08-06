package com.rag.notebook.system.controller;

import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.system.dto.LlmConfigRequest;
import com.rag.notebook.system.entity.LlmConfig;
import com.rag.notebook.system.service.LlmConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/llm")
public class LlmConfigController {

    private final LlmConfigService service;

    public LlmConfigController(LlmConfigService service) {
        this.service = service;
    }

    @GetMapping("/configs")
    public ApiResponse<List<LlmConfig>> list() {
        return ApiResponse.success(service.listAll());
    }

    @GetMapping("/configs/{id}")
    public ApiResponse<LlmConfig> get(@PathVariable Long id) {
        return ApiResponse.success(service.getById(id));
    }

    @PostMapping("/configs")
    public ApiResponse<LlmConfig> create(@RequestBody LlmConfigRequest req) {
        return ApiResponse.success(service.create(req));
    }

    @PutMapping("/configs/{id}")
    public ApiResponse<LlmConfig> update(@PathVariable Long id, @RequestBody LlmConfigRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    @DeleteMapping("/configs/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("删除成功");
    }

    @PutMapping("/configs/{id}/activate")
    public ApiResponse<LlmConfig> activate(@PathVariable Long id) {
        return ApiResponse.success(service.activate(id));
    }

    @PostMapping("/configs/test")
    public ApiResponse<Map<String, Object>> testConnection(@RequestBody LlmConfigRequest req) {
        return ApiResponse.success(service.testConnection(req));
    }

    @PostMapping("/configs/{id}/test")
    public ApiResponse<Map<String, Object>> testById(@PathVariable Long id) {
        return ApiResponse.success(service.testById(id));
    }

    @GetMapping("/configs/active")
    public ApiResponse<LlmConfig> getActive() {
        LlmConfig active = service.getActive();
        if (active == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(active);
    }
}
