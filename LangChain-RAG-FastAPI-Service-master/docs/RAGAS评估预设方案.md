# RAGAS 评估预设方案

> 参照 smallyoung.cn 的 RAGAS 四维评估方案，结合当前项目实际情况，设计一套完整的 RAG 质量评估体系。
> 补足原方案缺少的规则评估、自动诊断、批量定时评估、用户反馈、可视化看板等能力。

---

## 一、现状分析

### 1.1 当前项目已有的评估能力

| 能力 | 状态 | 说明 |
|------|------|------|
| 四指标 LLM 评估 | 已实现 | Context Precision / Context Recall / Faithfulness / Answer Relevancy |
| 规则评估 | 已实现 | 耗时、Token、相似度、回答长度等硬指标 |
| 自动诊断 | 已实现 | 根据四指标组合反推问题出在哪 |
| Trace 追踪 | 已实现 | RagTrace 记录全链路数据 |
| 用户反馈 | 已实现 | 1-5 分评分 + 原因 |
| 批量评估 | 已实现 | 每天凌晨 2 点自动评估 |
| 周报统计 | 已实现 | 每周一凌晨 3 点输出周报 |
| 评估 API | 已实现 | 反馈提交、报告查看、统计查询 |

### 1.2 当前实现的不足

| 不足 | 说明 |
|------|------|
| Faithfulness 粒度粗 | 整体打分 0-10，无声明拆分 |
| Answer Relevancy 无反向生成 | 直接判断，无反向生成问题+相似度计算 |
| Context Recall 无标准答案支持 | 只用 Question+Answer+Contexts，缺少 Ground Truth |
| 评估模型未隔离 | 复用通用 LLM，temperature=0.1，非确定性 |
| 综合评分用加权平均 | 对低分项不敏感 |
| 无可视化看板 | 评估结果只在数据库，无图表展示 |
| 评估结果无实时查询 | 批量评估后需手动查看报告 |

### 1.3 参照方案（smallyoung.cn）的优势

| 优势 | 说明 |
|------|------|
| 声明拆分 Prompt | Faithfulness 拆分为独立声明，逐个判断 SUPPORTED/NOT_SUPPORTED |
| temperature=0 | 专用评估模型，确定性输出 |
| 调和平均数 | 对低分更敏感，任何一个维度缺陷都会拉低综合分 |
| Micrometer 监控 | Gauge/Counter/Timer，可接入 Prometheus + Grafana |

### 1.4 参照方案的不足（本方案补足）

| 不足 | 本方案的补足 |
|------|------------|
| 无规则评估 | 保留现有规则评估（耗时、Token、相似度等） |
| 无自动诊断 | 保留现有自动诊断（四指标组合反推问题） |
| 无批量定时评估 | 保留现有 BatchEvaluationService |
| 无用户反馈 | 保留现有用户反馈评分 |
| 无 Trace 追踪 | 保留现有 RagTrace 实体 |
| 无可视化看板 | 新增前端评估看板页面 |
| 独立微服务 | 内嵌在主应用中，零额外部署 |

---

## 二、评估指标体系

### 2.1 四维 LLM 评估指标

| 指标 | 评估对象 | 需要 Ground Truth | 使用场景 | 计算方式 |
|------|---------|-----------------|---------|---------|
| Faithfulness | 生成器 | 否 | 生产 + 回归 | 声明拆分 → 逐个判断 SUPPORTED/NOT_SUPPORTED → 比例 |
| Answer Relevancy | 生成器 | 否 | 生产 + 回归 | 反向生成 3 个问题 → LLM 自评相似度 → 平均值 |
| Context Precision | 检索器 | 否 | 生产 + 回归 | 按位置加权：Precision@k × rel(k) |
| Context Recall | 检索器 | 是 | **仅回归测试** | 参考答案拆句 → 逐个检查覆盖率 → 比例 |

> **Context Recall 仅用于回归测试**：生产环境的真实查询没有标准答案，无法使用 Context Recall。该指标仅在有测试数据集的回归测试场景中使用。

### 2.2 规则评估指标

| 指标 | 计算方式 | 扣分规则 |
|------|---------|---------|
| 总耗时 | `totalLatencyMs` | >15s 扣 20，>10s 扣 15，>5s 扣 8 |
| 检索耗时 | `retrievalLatencyMs` | >5s 扣 10，>3s 扣 5 |
| 生成耗时 | `generationLatencyMs` | >10s 扣 15，>5s 扣 8 |
| 检索文档数 | `retrievedDocCount` | 为 0 扣 30 |
| 平均相似度 | `avgSimilarity` | <0.3 扣 15，<0.5 扣 8 |
| 回答长度 | `finalAnswer.length()` | <20 字扣 15，<50 字扣 8 |
| Token 用量 | `tokenUsed` | >80% 预算扣 10 |

### 2.3 综合评分

**生产环境**（无 Ground Truth）：
```
LLM 评估分 = Faithfulness × 0.4 + Answer Relevancy × 0.35 + Context Precision × 0.25
规则评估分 = 100 - 各项扣分（归一化为 0-1）

最终综合分 = 调和平均(LLM 评估分, 规则评估分)
```

**回归测试**（有 Ground Truth）：
```
LLM 评估分 = Faithfulness × 0.3 + Answer Relevancy × 0.3 + Context Precision × 0.2 + Context Recall × 0.2
规则评估分 = 100 - 各项扣分（归一化为 0-1）

最终综合分 = 调和平均(LLM 评估分, 规则评估分)
```

**权重可配置**：在 `application.yml` 中配置，不同部署环境可以调整：
```yaml
app.evaluation.weights:
  faithfulness: 0.4
  answer-relevancy: 0.35
  context-precision: 0.25
  context-recall: 0.2  # 仅回归测试使用
```

**调和平均数公式**：`H = n / (Σ 1/x_i)`

调和平均对低分更敏感：
- LLM 评估 0.9 + 规则评估 0.9 → 综合 0.9
- LLM 评估 0.9 + 规则评估 0.3 → 综合 0.45（加权平均是 0.6，调和平均更低）

**0 分保护**：指标为 0 时替换为 0.01，避免调和平均数崩溃。指标 < 0.1 时标记为"评估异常"。

### 2.4 评级标准

| 评级 | 综合分范围 | 说明 |
|------|-----------|------|
| 优秀 | ≥ 0.90 | 各维度表现良好，可放心使用 |
| 良好 | ≥ 0.75 | 基本合格，个别维度有优化空间 |
| 及格 | ≥ 0.60 | 存在明显问题，需要关注 |
| 不及格 | < 0.60 | 严重问题，需要立即优化 |

### 2.5 自动诊断规则

```
Faithfulness < 0.6
  → "忠实度低，存在幻觉。建议：优化 Prompt 约束，要求模型只基于检索内容回答"

Answer Relevancy < 0.6 且 Faithfulness ≥ 0.6
  → "答非所问，但回答本身忠实。建议：检查检索文档是否与问题匹配"

Context Precision < 0.6 且 Context Recall < 0.6
  → "检索精度和召回都低。建议：优化 Embedding 模型、Chunk 切分或召回策略"

Context Precision < 0.6 且 Context Recall ≥ 0.6
  → "检索到了不相关的文档。建议：优化 Reranker 或 TopK"

Context Recall < 0.6 且 Context Precision ≥ 0.6
  → "遗漏了关键文档。建议：优化 Query 扩展或降低 TopK 阈值"

规则评估耗时 > 10s
  → "响应过慢。建议：检查 LLM API 延迟、ChromaDB 连接、BM25 索引大小"

规则评估相似度 < 0.3
  → "检索文档与查询相似度低。建议：检查 Embedding 模型质量、分块策略"
```

---

## 三、核心实现方案

### 3.1 评估专用模型

在 `ModelFactory` 中新增评估专用模型：

```java
/**
 * 创建评估专用模型（temperature=0，确定性输出）
 * 用于 RAGAS 质量评估，保证同一输入多次评估结果一致
 */
public ChatLanguageModel createEvaluationModel() {
    return createChatModel(0.0);
}
```

**使用位置**：`EvaluationService` 中所有评估方法使用此模型。

### 3.2 Faithfulness 声明拆分

**Prompt 设计**：

```
你是一个严格的 RAG 系统评估专家。请评估以下回答的忠实度。

【用户问题】：{question}
【检索到的上下文】：{contexts}
【RAG 系统的回答】：{answer}

【评估任务】：
1. 将回答拆解为独立的原子声明（每行一条）
2. 对每条声明，判断上下文是否明确支持（SUPPORTED）或不支持（NOT_SUPPORTED）
3. 忠实度 = SUPPORTED 数量 / 总声明数量

请严格按以下格式输出：
CLAIMS:
- [声明1] -> SUPPORTED/NOT_SUPPORTED
- [声明2] -> SUPPORTED/NOT_SUPPORTED
FAITHFULNESS_SCORE: 0.XX
```

**解析逻辑**（多层降级）：
1. 第 1 层：精确正则匹配 `CLAIMS:` 和 `FAITHFULNESS_SCORE:`，逐行解析 `SUPPORTED/NOT_SUPPORTED`
2. 第 2 层：宽松正则匹配关键词 `SUPPORTED` / `NOT_SUPPORTED`（兼容格式偏差）
3. 第 3 层：只提取 `FAITHFULNESS_SCORE: 0.XX` 数值（忽略声明解析）
4. 第 4 层：整体打分降级（调用原有 evaluateFaithfulness 逻辑）

**Prompt 鲁棒性增强**：在 Prompt 中增加 2-3 个完整的输出示例，减少 LLM 格式偏差。解析失败时记录日志，用于后续优化 Prompt。

### 3.3 Answer Relevancy 反向生成

**Prompt 设计**：

```
你是一个严格的 RAG 系统评估专家。请评估以下回答的答案相关性。

【原始用户问题】：{question}
【RAG 系统的回答】：{answer}

【评估任务】：
1. 根据提供的回答，反向推导出 3 个可能的用户问题
2. 计算每个推导出的问题与原始问题的语义相似度（0.0 到 1.0）
3. 答案相关性 = 平均语义相似度

请严格按以下格式输出：
GENERATED_QUESTIONS:
- [问题1]（相似度: 0.XX）
- [问题2]（相似度: 0.XX）
- [问题3]（相似度: 0.XX）
ANSWER_RELEVANCY_SCORE: 0.XX
```

**说明**：得分直接使用 LLM 输出的相似度，不混合 Embedding 相似度。原因：Embedding 模型对中文的语义相似度计算质量参差不齐，混合后可能引入噪声。Embedding 相似度仅作为参考数据记录到报告中，不参与评分。

### 3.4 Context Recall 引入标准答案

**前置条件**：在 `RagTrace` 中增加 `groundTruth` 字段。

**Prompt 设计**：

```
你是一个严格的 RAG 系统评估专家。请评估上下文召回率。

【用户问题】：{question}
【标准参考答案】：{ground_truth}
【检索到的上下文】：{contexts}

【评估任务】：
1. 将标准参考答案拆解为独立的句子
2. 对每个句子，判断检索到的上下文是否包含能够支撑该句子的信息
3. 上下文召回率 = 被支持的句子数 / 总句子数

请严格按以下格式输出：
SENTENCES:
- [句子1] -> SUPPORTED/NOT_SUPPORTED
- [句子2] -> SUPPORTED/NOT_SUPPORTED
CONTEXT_RECALL_SCORE: 0.XX
```

**无 Ground Truth 时**：跳过 Context Recall，综合评分用其余三个指标的调和平均。

### 3.5 Context Precision 位置加权

**Prompt 设计**：

```
你是一个严格的 RAG 系统评估专家。请评估上下文精确度。

【用户问题】：{question}
【检索到的上下文（按排名顺序）】：
[位置1]
{context1}

[位置2]
{context2}

...

【RAG 系统的回答】：{answer}

【评估任务】：
判断每一段上下文是否与回答该问题相关（RELEVANT/NOT_RELEVANT）。
计算相关文档在排名中的精确度分布，得出加权得分。

请严格按以下格式输出：
RELEVANCE:
- [位置1] -> RELEVANT/NOT_RELEVANT
- [位置2] -> RELEVANT/NOT_RELEVANT
CONTEXT_PRECISION_SCORE: 0.XX
```

### 3.6 综合评分：调和平均数

```java
/**
 * 计算调和平均数
 * 公式：H = n / (Σ 1/x_i)
 * 特点：对低分更敏感，任何一个维度有明显缺陷，综合分都会被拉低
 */
public static double harmonicMean(double... scores) {
    double sum = 0.0;
    int count = 0;
    for (double s : scores) {
        if (s > 0) {
            sum += 1.0 / s;
            count++;
        }
    }
    return count == 0 ? 0.0 : count / sum;
}
```

**评分公式**：
```java
// LLM 评估分（四指标加权）
double llmScore = faithfulness * 0.3 + answerRelevancy * 0.3
                + contextPrecision * 0.2 + contextRecall * 0.2;

// 规则评估分（0-100 归一化为 0-1）
double ruleScore = ruleEvaluate(trace) / 100.0;

// 最终综合分（调和平均）
double totalScore = harmonicMean(llmScore, ruleScore);
```

---

## 四、评估流程

### 4.1 单条评估流程

```
输入：RagTrace（question, answer, contexts, ground_truth 可选）
  │
  ├─ Step 1: LLM 评估（3-4 个并行 LLM 调用，总耗时 1-3 秒）
  │   ├─ Faithfulness → 声明拆分 → SUPPORTED/NOT_SUPPORTED → 比例
  │   ├─ Answer Relevancy → 反向生成 3 问题 → LLM 自评相似度 → 平均值
  │   ├─ Context Precision → 位置加权 → 得分
  │   └─ Context Recall（需 Ground Truth，仅回归测试）→ 句子拆分 → 覆盖率
  │
  ├─ Step 2: 规则评估（无 LLM 调用，瞬时完成）
  │   ├─ 耗时检查
  │   ├─ Token 检查
  │   ├─ 相似度检查
  │   └─ 回答长度检查
  │
  ├─ Step 3: 综合评分
  │   ├─ LLM 评估分 = 指标加权（生产环境 3 指标，回归测试 4 指标）
  │   ├─ 规则评估分 = 100 - 扣分
  │   └─ 综合分 = 调和平均(LLM 评估分, 规则评估分)
  │
  ├─ Step 4: 评级 + 诊断
  │   ├─ 评级：优秀/良好/及格/不及格
  │   └─ 诊断：根据指标组合反推问题出在哪
  │
  └─ 输出：EvaluationReport
```

### 4.2 采样评估策略（降低成本）

不评估所有 Trace，按优先级分层：

| 优先级 | 条件 | 是否评估 |
|--------|------|---------|
| 高 | 用户给了低分反馈（1-2 分） | 必须评估 |
| 高 | 规则评估不及格（耗时 > 15s 或检索为空） | 必须评估 |
| 中 | 其他 Trace | 按 20% 比例采样评估 |
| 低 | 高相似度（>0.8）+ 短耗时（<5s） | 跳过（大概率没问题） |

**效果**：假设每天 100 条 Trace，实际评估约 25-30 条，LLM 调用从 400+ 次降到 100 次左右。

### 4.2 批量评估流程（已有，保留）

```
每天凌晨 2 点：
  → 查询前一天所有 RagTrace
  → 逐条执行评估
  → 保存 EvaluationReport
  → 输出低分样本统计

每周一凌晨 3 点：
  → 查询过去一周的 EvaluationReport
  → 计算平均分、各指标趋势、低分率
  → 输出周报日志
```

### 4.3 实时评估（异步 + 轮询，不引入 SSE）

参照方案使用 SSE 流式推送评估进度，但对当前项目来说是过度工程：
- 评估本身只需几秒，SSE 推送的价值不大
- 前端轮询即可获取结果，不需要引入 WebFlux
- 保持现有架构简单

**替代方案**：异步评估 + 前端轮询

```java
// 提交评估任务，返回 traceId（异步执行）
@PostMapping("/evaluation/evaluate")
public ApiResponse<Map<String, Object>> evaluate(@RequestParam String traceId) {
    CompletableFuture.runAsync(() -> {
        RagTrace trace = traceRepository.findById(traceId).orElse(null);
        if (trace == null) return;
        EvaluationReport report = evaluationService.evaluate(trace);
        reportRepository.save(report);
    }, taskExecutor);
    return ApiResponse.success(Map.of("traceId", traceId, "status", "processing"));
}

// 前端轮询查询结果
@GetMapping("/evaluation/report/{traceId}")
public ApiResponse<EvaluationReport> getReport(@PathVariable String traceId) {
    EvaluationReport report = reportRepository
        .findTopByTraceIdOrderByCreatedAtDesc(traceId).orElse(null);
    if (report == null) {
        return ApiResponse.success(null); // 评估中，前端继续轮询
    }
    return ApiResponse.success(report);
}
```

前端轮询策略：首次 1 秒后查询，之后每 2 秒查询一次，直到返回结果。

---

## 五、测试数据集方案

### 5.1 自动生成测试数据

用 LLM 从已有文档自动生成测试数据集，不需要手动标注：

```java
/**
 * 从知识库文档自动生成测试用例
 *
 * 流程：
 * 1. 从 MySQL 查询用户的所有知识库文档
 * 2. 对每篇文档，用 LLM 提取 3 个关键知识点
 * 3. 对每个知识点，用 LLM 生成 1 个问题
 * 4. 对每个问题，用 LLM 基于文档内容生成标准答案
 * 5. 保存到 evaluation_test_cases 表
 */
public List<TestCase> generateTestCases(String userId, int count) {
    List<KnowledgeDocument> docs = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    List<TestCase> testCases = new ArrayList<>();

    for (KnowledgeDocument doc : docs) {
        if (testCases.size() >= count) break;

        // Step 1: 提取知识点
        String keyPoints = llmCall(
            "请从以下文档中提取 3 个关键知识点（每个一行）：\n" + doc.getPreview()
        );

        // Step 2: 生成问题
        for (String point : keyPoints.split("\n")) {
            String question = llmCall(
                "基于以下知识点，生成一个具体的问答问题：\n知识点：" + point
            );

            // Step 3: 生成标准答案
            String groundTruth = llmCall(
                "基于以下文档内容，回答问题：\n文档：" + doc.getPreview() + "\n问题：" + question
            );

            testCases.add(new TestCase(question, groundTruth, doc.getId()));
        }
    }
    return testCases;
}
```

### 5.2 测试数据表结构

```sql
CREATE TABLE evaluation_test_cases (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(36),
    question        TEXT NOT NULL,
    ground_truth    TEXT NOT NULL,
    doc_id          VARCHAR(36),        -- 关联的知识库文档
    difficulty      VARCHAR(20),        -- simple / multi_hop / reasoning
    created_at      DATETIME,
    INDEX idx_user_id (user_id)
);
```

### 5.3 回归测试流程

```
1. 从 evaluation_test_cases 表读取测试用例
2. 对每个测试用例执行 RAG 检索 + 生成
3. 用 RAGAS 评估生成的回答
4. 与历史基线对比：
   - 综合分下降 > 5% → 告警
   - 单指标下降 > 10% → 告警
5. 输出回归测试报告
```

---

## 六、可视化看板方案

### 6.1 看板页面设计

新增前端页面 `/evaluation-dashboard`，包含以下区块：

```
┌─────────────────────────────────────────────────────────────┐
│                    RAG 质量评估看板                           │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ 综合评分  │ │ 忠实度    │ │ 答案相关性│ │ 上下文精确│       │
│  │  0.82    │ │  0.85    │ │  0.78    │ │  0.88    │       │
│  │  良好    │ │  ↑3%    │ │  ↓2%    │ │  ↑5%    │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  四维指标趋势图（折线图，最近 30 天）                  │   │
│  │  ─── Faithfulness  ─── Answer Relevancy              │   │
│  │  ─── Context Precision  ─── Context Recall           │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────┐ ┌──────────────────────┐        │
│  │  评级分布（饼图）      │ │  问题类型分布（柱状图）│        │
│  │  优秀 45%            │ │  幻觉 12%            │        │
│  │  良好 35%            │ │  答非所问 8%          │        │
│  │  及格 15%            │ │  检索遗漏 15%         │        │
│  │  不及格 5%           │ │  排序噪音 5%          │        │
│  └──────────────────────┘ └──────────────────────┘        │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  低分样本列表                                        │   │
│  │  | TraceID | 问题 | 综合分 | 诊断 | 操作 |           │   │
│  │  | xxx     | ... | 0.45  | 幻觉 | 详情 |           │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  评估耗时统计                                        │   │
│  │  平均评估耗时: 8.5s | Token 消耗: 1,234/次          │   │
│  │  今日评估次数: 156 | 低分率: 12%                     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 前端技术方案

| 区块 | 图表库 | 数据来源 |
|------|--------|---------|
| 四维指标卡片 | 纯 CSS 数字卡片 | `GET /evaluation/stats` |
| 趋势折线图 | ECharts | `GET /evaluation/trend?days=30` |
| 评级分布饼图 | ECharts | `GET /evaluation/distribution` |
| 问题类型柱状图 | ECharts | `GET /evaluation/diagnosis-stats` |
| 低分样本列表 | Vant Table | `GET /evaluation/low-scores` |
| 耗时统计 | 纯 CSS 数字卡片 | `GET /evaluation/stats` |

### 6.3 新增后端 API

| 端点 | 方法 | 功能 |
|------|------|------|
| `GET /evaluation/trend` | GET | 最近 N 天的四维指标趋势数据 |
| `GET /evaluation/distribution` | GET | 评级分布统计 |
| `GET /evaluation/diagnosis-stats` | GET | 问题类型分布统计 |
| `POST /evaluation/evaluate` | POST | 异步提交评估任务（前端轮询结果） |

### 6.4 趋势数据接口设计

```java
@GetMapping("/evaluation/trend")
public ApiResponse<Map<String, Object>> getTrend(
        @RequestParam(defaultValue = "30") int days) {

    LocalDateTime start = LocalDate.now().minusDays(days).atStartOfDay();
    List<EvaluationReport> reports = reportRepository.findByCreatedAtAfter(start);

    // 按日期分组，计算每日平均分
    Map<String, List<EvaluationReport>> grouped = reports.stream()
        .collect(Collectors.groupingBy(
            r -> r.getCreatedAt().toLocalDate().toString()
        ));

    List<String> dates = new ArrayList<>();
    List<Double> faithfulnessTrend = new ArrayList<>();
    List<Double> relevancyTrend = new ArrayList<>();
    List<Double> precisionTrend = new ArrayList<>();
    List<Double> recallTrend = new ArrayList<>();

    grouped.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> {
            dates.add(entry.getKey());
            List<EvaluationReport> dayReports = entry.getValue();
            faithfulnessTrend.add(avg(dayReports, EvaluationReport::getFaithfulness));
            relevancyTrend.add(avg(dayReports, EvaluationReport::getAnswerRelevancy));
            precisionTrend.add(avg(dayReports, EvaluationReport::getContextPrecision));
            recallTrend.add(avg(dayReports, EvaluationReport::getContextRecall));
        });

    return ApiResponse.success(Map.of(
        "dates", dates,
        "faithfulness", faithfulnessTrend,
        "answerRelevancy", relevancyTrend,
        "contextPrecision", precisionTrend,
        "contextRecall", recallTrend
    ));
}
```

---

## 七、实施计划

### Phase 1：评估逻辑增强（1-2 天）

- [ ] `ModelFactory` 新增 `createEvaluationModel()`（temperature=0）
- [ ] `EvaluationService` 替换为声明拆分 Prompt（Faithfulness），含多层降级解析
- [ ] `EvaluationService` 新增反向生成逻辑（Answer Relevancy），纯 LLM 自评
- [ ] `EvaluationService` 新增位置加权逻辑（Context Precision）
- [ ] `RagTrace` 新增 `groundTruth` 字段
- [ ] `EvaluationService` 支持 Ground Truth（Context Recall，仅回归测试）
- [ ] 综合评分改为调和平均数，支持 0 分保护
- [ ] 权重配置化（`application.yml`）
- [ ] 评估采样策略（高优先级必须评估，其他按比例采样）
- [ ] LLM 评估调用并行化（CompletableFuture.allOf）

### Phase 2：测试数据集与回归测试（1 天）

- [ ] 新建 `evaluation_test_cases` 表
- [ ] 实现 `TestCaseGenerator`（LLM 自动生成测试用例）
- [ ] 实现手动触发回归测试 API（`POST /evaluation/regression`）
- [ ] 回归测试结果与上一次对比（而非与历史平均值对比）
- [ ] 回归测试使用完整四指标（含 Context Recall）

### Phase 3：可视化看板（2-3 天）

- [ ] 后端新增趋势/分布/诊断统计 API
- [ ] 后端新增异步评估 + 轮询查询接口
- [ ] 前端新建 `/evaluation-dashboard` 页面
- [ ] 前端集成 ECharts 图表
- [ ] 前端低分样本列表 + 详情弹窗

### Phase 4：优化迭代（持续）

- [ ] 根据用户反馈校准 LLM 评估 Prompt
- [ ] 调整四指标权重
- [ ] 优化评估速度（并行 LLM 调用）
- [ ] 评估结果导出（CSV/JSON）

---

## 八、苏格拉底式提问与方案优化

### Q1：Ground Truth 从哪来？生产环境有标准答案吗？

**问题**：方案说 Context Recall 需要 Ground Truth，但生产环境中用户的 RAG 查询是没有标准答案的。测试数据集可以自动生成，但生产环境的真实查询怎么办？

**反思**：Context Recall 在生产环境中几乎无法使用，因为：
- 真实用户提问不会有预先准备的标准答案
- 即使用 LLM 自动生成 Ground Truth，那也是"用 LLM 生成的答案评估 LLM 生成的答案"，失去了评估意义

**优化**：
- Context Recall **仅用于回归测试**（有测试数据集的场景），不用于生产环境的实时评估
- 生产环境的评估只用三个不需要 Ground Truth 的指标：Faithfulness、Answer Relevancy、Context Precision
- 综合评分公式调整为：
  ```
  生产环境：综合分 = 调和平均(Faithfulness, Answer Relevancy, Context Precision, 规则评估分)
  回归测试：综合分 = 调和平均(Faithfulness, Answer Relevancy, Context Precision, Context Recall, 规则评估分)
  ```

### Q2：每次评估 4-5 个 LLM 调用，成本可控吗？

**问题**：每次评估需要 4 个 LLM 调用（Faithfulness + Answer Relevancy + Context Precision + Context Recall），加上 Answer Relevancy 反向生成问题的额外调用。每天评估 100 条 Trace 就是 500 次 LLM 调用，这个成本是否可承受？

**反思**：
- 以 GLM-4 为例，每次调用约 0.01-0.05 元，500 次约 5-25 元/天，月成本 150-750 元
- 但评估用的是 temperature=0 的精确模型，Token 消耗比生成任务少
- 真正的问题是：**是否每条 Trace 都需要评估？**

**优化**：
- **采样评估**：不评估所有 Trace，按比例采样（如 20%），降低成本
- **分层评估**：
  - 用户给了低分反馈（1-2 分）的 Trace → 必须评估（找出问题）
  - 高相似度（>0.8）+ 短耗时（<5s）的 Trace → 跳过评估（大概率没问题）
  - 其他 Trace → 按 20% 比例采样评估
- **并行调用**：4 个 LLM 评估调用并行执行，总耗时从 4-12 秒降到 1-3 秒

### Q3：temperature=0 就能保证评估结果确定吗？

**问题**：即使 temperature=0，LLM 的输出也不是完全确定性的（可能受 API 负载、网络等因素影响）。同一 Trace 多次评估结果可能不同。

**反思**：确实，temperature=0 只是减少了随机性，但不能完全消除。实际测试中，同一输入多次调用可能得到 0.85 和 0.87 这样微小的差异。

**优化**：
- **评估结果置信区间**：每个指标报告为 `0.85 ± 0.02`，而非单一数值
- **多次评估取平均**：对关键 Trace（如低分样本）执行 3 次评估，取中位数
- **阈值缓冲**：评级边界加缓冲区（如"良好"的阈值从 0.75 改为 0.73-0.77 之间视为"良好"）

### Q4：声明拆分 Prompt 的输出格式不稳定怎么办？

**问题**：Prompt 要求 LLM 按 `CLAIMS:\n- [声明] -> SUPPORTED/NOT_SUPPORTED` 格式输出，但 LLM 的输出格式并不总是稳定的。如果输出格式不符合预期，正则解析会失败。

**反思**：这是 LLM-as-a-judge 的核心风险。常见的格式偏差：
- LLM 输出 `1. 声明1 - SUPPORTED` 而非 `- [声明1] -> SUPPORTED`
- LLM 输出中文标签 `支持/不支持` 而非 `SUPPORTED/NOT_SUPPORTED`
- LLM 在 CLAIMS 前加了额外文字

**优化**：
- **多层降级解析**：
  ```
  第 1 层：精确正则匹配 "CLAIMS:" 和 "FAITHFULNESS_SCORE:"
  第 2 层：宽松正则匹配 "SUPPORTED" / "NOT_SUPPORTED" 关键词
  第 3 层：提取 FAITHFULNESS_SCORE 数值（忽略声明解析）
  第 4 层：整体打分降级（调用原有 evaluateFaithfulness 逻辑）
  ```
- **Prompt 中增加格式示例**：在 Prompt 中给出 2-3 个完整的输出示例，减少格式偏差
- **解析失败时记录日志**：收集格式偏差案例，用于后续优化 Prompt

### Q5：四指标权重的依据是什么？

**问题**：Faithfulness 0.3 + Answer Relevancy 0.3 + Context Precision 0.2 + Context Recall 0.2，这个权重分配的依据是什么？不同场景是否应该有不同的权重？

**反思**：权重分配确实缺乏依据。不同场景对指标的敏感度不同：
- **知识问答场景**：Faithfulness 最重要（不能编造），Answer Relevancy 次之
- **创意写作场景**：Answer Relevancy 最重要（要回答到点上），Faithfulness 可以放宽
- **文档检索场景**：Context Precision 最重要（检索结果要精准）

**优化**：
- **默认权重保持不变**（通用场景）
- **支持权重配置化**：在 `application.yml` 中配置权重，不同部署环境可以调整
  ```yaml
  app.evaluation.weights:
    faithfulness: 0.3
    answer-relevancy: 0.3
    context-precision: 0.2
    context-recall: 0.2
  ```
- **后续根据用户反馈数据校准**：分析用户低分反馈与四指标的相关性，用回归分析自动优化权重

### Q6：调和平均数遇到 0 分怎么办？

**问题**：如果某个指标为 0（比如评估解析失败返回默认值 0.5，或者某个指标确实很差），调和平均数会严重被拉低。如果指标为 0，调和平均数直接变成 0。

**反思**：当前方案中 `harmonicMean()` 方法用 `if (s > 0)` 过滤了 0 分，但这意味着 0 分的指标被完全忽略，这不合理。

**优化**：
- **0 分保护**：将 0 分替换为最小值 0.01，避免调和平均数崩溃
- **异常值处理**：如果某个指标 < 0.1，标记为"评估异常"而非直接参与计算
- **降级策略**：如果 LLM 评估解析失败，该指标不参与综合评分，用剩余指标的调和平均

### Q7：评估延迟对用户体验的影响？

**问题**：单条评估需要 4 个 LLM 调用，每个约 1-3 秒，串行执行需要 4-12 秒。如果用户提交反馈后等待评估结果，体验很差。

**反思**：用户不应该等待评估结果。评估是后台任务，用户只需要提交反馈，评估结果后续查看即可。

**优化**：
- **评估完全异步化**：用户提交反馈 → 立即返回成功 → 后台异步执行评估 → 结果写入数据库
- **不阻塞用户操作**：评估任务提交到线程池，不影响 RAG 调用的响应速度
- **评估结果通知**：低分评估结果可以通过站内消息或邮件通知开发者（可选）

### Q8：Embedding 相似度增强是否必要？

**问题**：Answer Relevancy 的反向生成方案中，LLM 自评相似度和 Embedding 相似度各占 0.5。但 Embedding 模型的质量直接影响这个评估的准确性。如果 Embedding 模型本身质量不高，这个增强是否反而会引入噪声？

**反思**：当前项目用的 Embedding 模型（智谱/通义千问）对中文的支持质量参差不齐。如果 Embedding 模型对"量子纠缠"和"粒子间关联"的相似度计算不准确，反而会拉低评估质量。

**优化**：
- **默认只用 LLM 自评**：Answer Relevancy 的得分直接用 LLM 输出的相似度，不混合 Embedding
- **Embedding 相似度作为参考**：记录 Embedding 相似度到报告中，但不参与评分计算
- **后续如果 Embedding 模型升级**，再考虑混合评分

### Q9：回归测试什么时候触发？

**问题**：方案说"综合分下降 > 5% → 告警"，但什么时候触发回归测试？每次 RAG 优化后？每天？每次部署？

**反思**：回归测试应该是**手动触发**的，而非自动执行。因为：
- 回归测试需要调用 RAG 系统生成回答，消耗真实 Token
- 自动执行可能在非工作时间浪费资源
- 回归测试应该在 RAG 优化后、部署前执行

**优化**：
- **手动触发**：提供 `POST /evaluation/regression` API，开发者在 RAG 优化后手动触发
- **CI/CD 集成**（可选）：在部署流水线中加入回归测试步骤
- **结果对比**：与上一次回归测试结果对比，而非与历史平均值对比

### Q10：评估结果如何闭环到 RAG 优化？

**问题**：方案只是诊断问题（"忠实度低 → 幻觉"），但没有说如何根据评估结果自动优化 RAG 参数。评估的意义是什么？

**反思**：评估本身不是目的，**指导优化**才是。当前方案的诊断只是告诉开发者"哪里有问题"，但没有告诉"怎么改"。

**优化**：
- **诊断建议具体化**：不只说"优化 Prompt"，而是给出具体的 Prompt 修改建议
- **A/B 测试支持**：记录评估结果时同时记录 RAG 配置版本（如 `rag_config_v1`），支持不同配置的效果对比
- **优化效果追踪**：每次 RAG 优化后，对比优化前后的评估指标变化，量化优化效果

---

## 九、与参照方案的差异总结

| 维度 | 参照方案（smallyoung） | 本方案 |
|------|----------------------|--------|
| 架构 | 独立微服务 | 内嵌在主应用中 |
| 评估模型 | temperature=0 | temperature=0（新增专用模型） |
| Faithfulness | 声明拆分 + SUPPORTED/NOT_SUPPORTED | 同（借鉴） |
| Answer Relevancy | 反向生成 + LLM 自评相似度 | 反向生成 + Embedding 相似度增强 |
| Context Recall | 需要 Ground Truth | 同（新增 Ground Truth 支持） |
| Context Precision | 位置加权 | 同（借鉴） |
| 综合评分 | 调和平均数 | 调和平均数（借鉴） |
| 规则评估 | 无 | 有（耗时、Token、相似度等） |
| 自动诊断 | 无 | 有（四指标组合反推问题） |
| 批量定时评估 | 无 | 有（每天/每周） |
| 用户反馈 | 无 | 有（1-5 分 + 原因） |
| Trace 追踪 | 无 | 有（RagTrace 全链路） |
| 测试数据集 | 无 | 有（LLM 自动生成） |
| 监控 | Micrometer + Prometheus | 前端看板（ECharts） |
| 评估结果获取 | SSE 流式推送 | 异步提交 + 前端轮询 |
| 回归测试 | 无 | 有（基线对比 + 告警） |
