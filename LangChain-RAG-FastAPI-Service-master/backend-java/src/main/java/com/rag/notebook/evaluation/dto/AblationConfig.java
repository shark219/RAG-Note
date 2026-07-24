package com.rag.notebook.evaluation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消融实验配置 —— 控制 RAG 流水线中各组件的开关
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AblationConfig {

    /** 实验编号，如 R-1, R-2 */
    private String experimentId;

    /** 实验名称，如 "w/o Query Expander" */
    private String experimentName;

    /** 消融组件名称 */
    private String ablationComponent;

    @Builder.Default
    private boolean queryExpansionEnabled = true;

    @Builder.Default
    private boolean vectorSearchEnabled = true;

    @Builder.Default
    private boolean bm25SearchEnabled = true;

    @Builder.Default
    private boolean rrfFusionEnabled = true;

    @Builder.Default
    private boolean rerankEnabled = true;

    @Builder.Default
    private boolean sourceAttributionEnabled = true;

    @Builder.Default
    private boolean retrievalReviewEnabled = false;

    /** null 表示使用默认 chunk_size */
    private Integer chunkSize;

    /** null 表示使用默认 chunk_overlap */
    private Integer chunkOverlap;

    /** null 表示使用默认 topK */
    private Integer topK;

    /**
     * 创建完整流水线的基线配置
     */
    public static AblationConfig baseline() {
        return AblationConfig.builder()
                .experimentId("BASELINE")
                .experimentName("完整流水线")
                .ablationComponent("none")
                .build();
    }

    /**
     * 创建预设的 RAG 消融实验配置
     */
    public static AblationConfig[] allRagExperiments() {
        return new AblationConfig[]{
                AblationConfig.builder()
                        .experimentId("R-1")
                        .experimentName("w/o Query Expander")
                        .ablationComponent("QueryExpander")
                        .queryExpansionEnabled(false)
                        .build(),
                AblationConfig.builder()
                        .experimentId("R-2")
                        .experimentName("w/o Vector Search")
                        .ablationComponent("VectorSearch")
                        .vectorSearchEnabled(false)
                        .build(),
                AblationConfig.builder()
                        .experimentId("R-3")
                        .experimentName("w/o BM25 Search")
                        .ablationComponent("BM25Search")
                        .bm25SearchEnabled(false)
                        .build(),
                AblationConfig.builder()
                        .experimentId("R-4")
                        .experimentName("w/o RRF Fusion")
                        .ablationComponent("RRFFusion")
                        .rrfFusionEnabled(false)
                        .build(),
                AblationConfig.builder()
                        .experimentId("R-5")
                        .experimentName("w/o Reranker")
                        .ablationComponent("Reranker")
                        .rerankEnabled(false)
                        .build(),
                AblationConfig.builder()
                        .experimentId("R-6")
                        .experimentName("w/o Source Attribution")
                        .ablationComponent("SourceAttribution")
                        .sourceAttributionEnabled(false)
                        .build(),
                AblationConfig.builder()
                        .experimentId("R-7")
                        .experimentName("w/o Retrieval QualityReviewer")
                        .ablationComponent("QualityReviewer")
                        .retrievalReviewEnabled(false)
                        .build(),
                // R-8 和 R-9 是参数对比实验，由调用方传入不同参数
        };
    }

    /**
     * 创建不同 Chunk 粒度的实验配置
     */
    public static AblationConfig[] chunkSizeExperiments() {
        return new AblationConfig[]{
                AblationConfig.builder().experimentId("R-8a").experimentName("chunk_size=100").ablationComponent("ChunkSize").chunkSize(100).chunkOverlap(20).build(),
                AblationConfig.builder().experimentId("R-8b").experimentName("chunk_size=200").ablationComponent("ChunkSize").chunkSize(200).chunkOverlap(20).build(),
                AblationConfig.builder().experimentId("R-8c").experimentName("chunk_size=400").ablationComponent("ChunkSize").chunkSize(400).chunkOverlap(40).build(),
                AblationConfig.builder().experimentId("R-8d").experimentName("chunk_size=800").ablationComponent("ChunkSize").chunkSize(800).chunkOverlap(80).build(),
        };
    }

    /**
     * 创建不同 Top-K 的实验配置
     */
    public static AblationConfig[] topKExperiments() {
        return new AblationConfig[]{
                AblationConfig.builder().experimentId("R-9a").experimentName("topK=3").ablationComponent("TopK").topK(3).build(),
                AblationConfig.builder().experimentId("R-9b").experimentName("topK=5").ablationComponent("TopK").topK(5).build(),
                AblationConfig.builder().experimentId("R-9c").experimentName("topK=10").ablationComponent("TopK").topK(10).build(),
                AblationConfig.builder().experimentId("R-9d").experimentName("topK=20").ablationComponent("TopK").topK(20).build(),
        };
    }
}
