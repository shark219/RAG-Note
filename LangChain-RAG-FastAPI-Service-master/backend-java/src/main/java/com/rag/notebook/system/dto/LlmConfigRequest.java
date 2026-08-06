package com.rag.notebook.system.dto;

import lombok.Data;

@Data
public class LlmConfigRequest {
    private String name;
    private String provider;
    private String model;
    private String actualModel;
    private String apiUrl;
    private String apiKey;
}
