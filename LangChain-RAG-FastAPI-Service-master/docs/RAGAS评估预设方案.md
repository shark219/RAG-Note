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
| 评估进度无实时反馈 | 批量评估无 SSE 推送 |

### 1.3 参照方案（smallyoung.cn）的优势

| 优势 | 说明 |
|------|------|
| 声明拆分 Prompt | Faithfulness 拆分为独立声明，逐个判断 SUPPORTED/NOT_SUPPORTED |
| temperature=0 | 专用评估模型，确定性输出 |
| 调和平均数 | 对低分更敏感，任何一个维度缺陷都会拉低综合分 |
| Micrometer 监控 | Gauge/Counter/Timer，可接入 Prometheus + Grafana |
| SSE 流式推送 | 评估进度实时推送到前端 |

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

| 指标 | 评估对象 | 需要 Ground Truth | 计算方式 |
|------|---------|-----------------|---------|
| Faithfulness | 生成器 | 否 | 声明拆分 → 逐个判断 SUPPORTED/NOT_SUPPORTED → 比例 |
| Answer Relevancy | 生成器 | 否 | 反向生成 3 个问题 → Embedding 相似度 → 平均值 |
| Context Recall | 检索器 | 是 | 参考答案拆句 → 逐个检查覆盖率 → 比例 |
| Context Precision | 检索器 | 否 | 按位置加权：Precision@k × rel(k) |

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

```
LLM 评估分 = Faithfulness × 0.3 + Answer Relevancy × 0.3 + Context Precision × 0.2 + Context Recall × 0.2
规则评估分 = 100 - 各项扣分

最终综合分 = 调和平均(LLM 评估分, 规则评估分)
```

**调和平均数公式**：`H = 2 / (1/x1 + 1/x2)`

调和平均对低分更敏感：
- LLM 评估 0.9 + 规则评估 0.9 → 综合 0.9
- LLM 评估 0.9 + 规则评估 0.3 → 综合 0.45（加权平均是 0.6，调和平均更低）

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

**解析逻辑**：
1. 用正则提取 `CLAIMS:` 到 `FAITHFULNESS_SCORE:` 之间的内容
2. 逐行解析 `SUPPORTED` / `NOT_SUPPORTED`
3. 计算比例：`SUPPORTED 数 / 总声明数`
4. 如果解析失败，降级为整体打分

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

**增强**：除了 LLM 自评相似度，还可以用 Embedding 计算实际余弦相似度：
```java
// 用 Embedding 模型计算反向生成问题与原始问题的相似度
double embeddingSimilarity = cosineSimilarity(
    embed(originalQuestion),
    embed(generatedQuestion)
);
// 最终得分 = LLM 自评 × 0.5 + Embedding 相似度 × 0.5
```

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
  ├─ Step 1: LLM 评估（4 个并行 LLM 调用）
  │   ├─ Faithfulness → 声明拆分 → SUPPORTED/NOT_SUPPORTED → 比例
  │   ├─ Answer Relevancy → 反向生成 3 问题 → 相似度 → 平均值
  │   ├─ Context Precision → 位置加权 → 得分
  │   └─ Context Recall（需 Ground Truth）→ 句子拆分 → 覆盖率
  │
  ├─ Step 2: 规则评估（无 LLM 调用）
  │   ├─ 耗时检查
  │   ├─ Token 检查
  │   ├─ 相似度检查
  │   └─ 回答长度检查
  │
  ├─ Step 3: 综合评分
  │   ├─ LLM 评估分 = 四指标加权
  │   ├─ 规则评估分 = 100 - 扣分
  │   └─ 综合分 = 调和平均(LLM 评估分, 规则评估分)
  │
  ├─ Step 4: 评级 + 诊断
  │   ├─ 评级：优秀/良好/及格/不及格
  │   └─ 诊断：根据四指标组合反推问题出在哪
  │
  └─ 输出：EvaluationReport
```

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

### 4.3 SSE 实时评估（新增）

新增 `/evaluation/evaluate/stream` 端点，评估过程中实时推送每个指标的进度：

```java
@GetMapping(value = "/evaluation/evaluate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter evaluateStream(@RequestParam String traceId) {
    SseEmitter emitter = new SseEmitter(120000L);
    CompletableFuture.runAsync(() -> {
        RagTrace trace = traceRepository.findById(traceId).orElse(null);
        if (trace == null) {
            sendEvent(emitter, "error", "Trace 不存在");
            return;
        }

        sendEvent(emitter, "progress", "开始评估...");

        // 逐个指标评估并推送
        sendEvent(emitter, "progress", "评估忠实度...");
        double faithfulness = evaluateFaithfulness(trace);
        sendEvent(emitter, "metric", Map.of("name", "faithfulness", "score", faithfulness));

        sendEvent(emitter, "progress", "评估答案相关性...");
        double answerRelevancy = evaluateAnswerRelevancy(trace);
        sendEvent(emitter, "metric", Map.of("name", "answer_relevancy", "score", answerRelevancy));

        // ... 其他指标

        sendEvent(emitter, "completed", Map.of("totalScore", totalScore, "level", level));
        emitter.complete();
    }, taskExecutor);
    return emitter;
}
```

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
| `GET /evaluation/evaluate/stream` | GET | SSE 实时评估进度 |

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
- [ ] `EvaluationService` 替换为声明拆分 Prompt（Faithfulness）
- [ ] `EvaluationService` 新增反向生成逻辑（Answer Relevancy）
- [ ] `EvaluationService` 新增位置加权逻辑（Context Precision）
- [ ] `RagTrace` 新增 `groundTruth` 字段
- [ ] `EvaluationService` 支持 Ground Truth（Context Recall）
- [ ] 综合评分改为调和平均数

### Phase 2：测试数据集（1 天）

- [ ] 新建 `evaluation_test_cases` 表
- [ ] 实现 `TestCaseGenerator`（LLM 自动生成测试用例）
- [ ] 实现回归测试流程

### Phase 3：可视化看板（2-3 天）

- [ ] 后端新增趋势/分布/诊断统计 API
- [ ] 后端新增 SSE 实时评估接口
- [ ] 前端新建 `/evaluation-dashboard` 页面
- [ ] 前端集成 ECharts 图表
- [ ] 前端低分样本列表 + 详情弹窗

### Phase 4：优化迭代（持续）

- [ ] 根据用户反馈校准 LLM 评估 Prompt
- [ ] 调整四指标权重
- [ ] 优化评估速度（并行 LLM 调用）
- [ ] 评估结果导出（CSV/JSON）

---

## 八、与参照方案的差异总结

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
| SSE 流式 | WebFlux Flux | SseEmitter |
| 回归测试 | 无 | 有（基线对比 + 告警） |
