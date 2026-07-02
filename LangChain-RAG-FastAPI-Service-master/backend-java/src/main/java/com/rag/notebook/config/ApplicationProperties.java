package com.rag.notebook.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class ApplicationProperties {

    private Jwt jwt = new Jwt();
    private Llm llm = new Llm();
    private Embed embed = new Embed();
    private Vision vision = new Vision();
    private Chroma chroma = new Chroma();
    private Reranker reranker = new Reranker();
    private Md5 md5 = new Md5();
    private String extractedImagesDir = "data/extracted_images";
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class Jwt {
        private String secret = "a3f5b8c1d9e2f4a7b0c3d6e9f1a4b7c0d3e6f9a2b5c8d1e4f7a0b3c6d9e2f5b8c1d4e7f0a3b6c9";
        private String algorithm = "HS256";
        private long expiration = 86400000;
    }

    @Data
    public static class Llm {
        private String type = "ZHIPU";
        private Zhipu zhipu = new Zhipu();
        private Ollama ollama = new Ollama();
        private Aliyun aliyun = new Aliyun();

        @Data
        public static class Zhipu {
            private String apiKey = "";
            private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
            private String model = "glm-4-flash";
        }

        @Data
        public static class Ollama {
            private String baseUrl = "http://localhost:11434";
            private String model = "qwen3.5:0.8b";
        }

        @Data
        public static class Aliyun {
            private String apiKey = "";
            private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            private String model = "qwen3-max";
        }
    }

    @Data
    public static class Embed {
        private String type = "ZHIPU";
        private EmbedZhipu zhipu = new EmbedZhipu();
        private Llm.Ollama ollama = new Llm.Ollama();
        private EmbedAliyun aliyun = new EmbedAliyun();

        @Data
        public static class EmbedZhipu {
            private String apiKey = "";
            private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
            private String model = "embedding-3";
        }

        @Data
        public static class EmbedAliyun {
            private String apiKey = "";
            private String model = "qwen3-embedding";
        }
    }

    @Data
    public static class Vision {
        private String type = "";
        private VisionAliyun aliyun = new VisionAliyun();
        private VisionOllama ollama = new VisionOllama();
        private int batchSize = 5;
        private Dedup dedup = new Dedup();

        @Data
        public static class VisionAliyun {
            private String model = "qwen-vl-max";
        }

        @Data
        public static class VisionOllama {
            private String model = "qwen-vl:7b";
        }

        @Data
        public static class Dedup {
            private boolean enabled = true;
            private int threshold = 10;
        }
    }

    @Data
    public static class Chroma {
        private String url = "http://localhost:8001";
        private String collection = "rag_collection";
        private String notesCollection = "notes_collection";
        private String persistDir = "data/chromadb";
        private int k = 5;
        private int chunkSize = 200;
        private int chunkOverlap = 20;
    }

    @Data
    public static class Reranker {
        private String modelPath = "";
    }

    @Data
    public static class Md5 {
        private String storeDir = "data/md5_hex_store";
    }

    @Data
    public static class RateLimit {
        private boolean enabled = false;
    }
}
