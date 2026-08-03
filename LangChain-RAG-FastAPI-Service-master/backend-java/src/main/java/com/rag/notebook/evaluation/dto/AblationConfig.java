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

    /** null 表示使用默认 topK */
    private Integer topK;

    /** RRF 融合公式中的常数 k，null 表示使用默认值 */
    private Integer rrfK;

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
     * RAG Pipeline 消融实验（BASELINE + R-1~R-5）
     * 覆盖在线 RAG 检索链路：Query理解 → 召回 → 融合 → 排序 → 生成上下文
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
        };
    }

    /**
     * RAG Pipeline 消融实验全集（含基线），用于统一入口
     */
    public static AblationConfig[] ragPipelineExperiments() {
        return new AblationConfig[]{
                baseline(),
                allRagExperiments()[0], // R-1
                allRagExperiments()[1], // R-2
                allRagExperiments()[2], // R-3
                allRagExperiments()[3], // R-4
                allRagExperiments()[4], // R-5
        };
    }

    /**
     * 索引参数实验（TopK 对比）—— 不属于 RAG 组件消融，独立运行
     */
    public static AblationConfig[] topKExperiments() {
        return new AblationConfig[]{
                AblationConfig.builder().experimentId("R-6a").experimentName("topK=3").ablationComponent("TopK").topK(3).build(),
                AblationConfig.builder().experimentId("R-6b").experimentName("topK=5").ablationComponent("TopK").topK(5).build(),
                AblationConfig.builder().experimentId("R-6c").experimentName("topK=10").ablationComponent("TopK").topK(10).build(),
                AblationConfig.builder().experimentId("R-6d").experimentName("topK=20").ablationComponent("TopK").topK(20).build(),
        };
    }

    /**
     * 检索参数实验（RRF_K 对比）—— 不属于 RAG 组件消融，独立运行。
     * RRF_K 越小，排名靠前结果的分数优势越明显（更陡峭）；越大则各排名分数差异越平缓。
     */
    public static AblationConfig[] rrfKExperiments() {
        return new AblationConfig[]{
                AblationConfig.builder().experimentId("R-7a").experimentName("rrf_k=10").ablationComponent("RrfK").rrfK(10).build(),
                AblationConfig.builder().experimentId("R-7b").experimentName("rrf_k=30").ablationComponent("RrfK").rrfK(30).build(),
                AblationConfig.builder().experimentId("R-7c").experimentName("rrf_k=60").ablationComponent("RrfK").rrfK(60).build(),
                AblationConfig.builder().experimentId("R-7d").experimentName("rrf_k=100").ablationComponent("RrfK").rrfK(100).build(),
        };
    }
}
