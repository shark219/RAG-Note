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
    private Evaluation evaluation = new Evaluation();
    private Ablation ablation = new Ablation();

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
        private DeepSeek deepseek = new DeepSeek();

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

        @Data
        public static class DeepSeek {
            private String apiKey = "";
            private String baseUrl = "https://api.deepseek.com/v1";
            private String model = "deepseek-chat";
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
        private boolean enabled = false;
        private String type = "ZHIPU";
        private String apiKey = "";
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        private String model = "rerank";
        private double scoreThreshold = 0.5;
        private int topN = 5;
    }

    @Data
    public static class Md5 {
        private String storeDir = "data/md5_hex_store";
    }

    @Data
    public static class RateLimit {
        private boolean enabled = false;
    }

    @Data
    public static class Evaluation {
        private double faithfulnessWeight = 0.4;
        private double answerRelevancyWeight = 0.35;
        private double contextPrecisionWeight = 0.25;
        private double contextRecallWeight = 0.2;
        private double sampleRate = 0.2;
    }

    @Data
    public static class Ablation {
        private RagAblation rag = new RagAblation();

        @Data
        public static class RagAblation {
            /** 是否启用 Query 扩展 */
            private boolean queryExpansionEnabled = true;
            /** 是否启用向量检索（ChromaDB） */
            private boolean vectorSearchEnabled = true;
            /** 是否启用 BM25 关键词检索 */
            private boolean bm25SearchEnabled = true;
            /** 是否启用 RRF 融合 */
            private boolean rrfFusionEnabled = true;
            /** 是否启用 Cross-Encoder 精排 */
            private boolean rerankEnabled = true;
            /** 是否启用来源标注 */
            private boolean sourceAttributionEnabled = true;
            /** 是否启用检索质量审查 */
            private boolean retrievalReviewEnabled = false;
            /** Chunk 大小（消融实验可覆盖默认值） */
            private Integer chunkSize;
            /** Chunk 重叠量（消融实验可覆盖默认值） */
            private Integer chunkOverlap;
            /** Top-K 返回数量（消融实验可覆盖默认值） */
            private Integer topK;
        }
    }
}
