# RAG 架构设计笔记

## 一、整体架构概览

```
用户问题
   │
   ▼
┌─────────────────────────────────┐
│  Agent（Function Calling 循环）    │  ← LLM 自主决定是否调用工具
│  main_prompt.txt 定义决策规则       │
└───────────┬─────────────────────┘
            │ 调用 ragSummary 工具
            ▼
┌─────────────────────────────────┐
│  1. HyDE 查询优化                 │  ← LLM 生成假设性文档
│  2. 双路向量检索                   │  ← 知识库 + 笔记 并行检索
│  3. 结果融合 + 来源标注            │
│  4. MapReduce 并发摘要             │  ← CompletableFuture 并发
│  5. 二次融合生成最终回答            │
└─────────────────────────────────┘
```

---

## 二、文档处理阶段（DocumentProcessor）

### 2.1 多格式解析

**设计决策**：使用 Apache Tika 统一解析 txt/pdf/md/pptx/docx

**解决的问题**：不同格式需要不同解析器，维护成本高。Tika 是一个通用文档解析库，一套代码处理所有格式。

**流程**：
```
文件上传 → Tika.parseToString(file) → 纯文本 → 切片 → 向量化存储
```

### 2.2 MD5 去重

**设计决策**：上传前计算文件 MD5，跳过已存在的文档

**解决的问题**：用户重复上传同一文件会导致向量库中出现大量重复切片，浪费存储且污染检索结果。

**实现细节**：
- 双重校验：MD5 记录存在 **且** 向量数据存在才跳过
- 防止"重启后向量数据丢失但 MD5 记录还在"的边界情况
- 如果 MD5 记录存在但向量数据丢失，清理旧记录后重新处理

### 2.3 中文感知切片（Splitting）

**设计决策**：按中文标点符号层级切片，而非固定字符数硬切

**解决的问题**：英文 RAG 常用的 `RecursiveCharacterTextSplitter` 按空格/换行切分，对中文效果很差——会把一个句子从中间切断，破坏语义完整性。

**切片策略**（按优先级尝试分隔符）：
```
"\n\n" → "\n" → "。" → "！" → "？" → "." → "!" → "?" → "；" → ";" → "，" → ","
```

**流程**：
1. 先尝试用 `\n\n`（段落）分割
2. 如果分割后只有一段（说明没有段落分隔），降级到 `\n`
3. 依次降级，直到找到能产生多段的分隔符
4. 然后按 chunkSize（200字符）合并小段，超过时开启新 chunk
5. chunkOverlap（20字符）保证相邻 chunk 有重叠，避免边界丢失上下文

**为什么需要 overlap**：
```
chunk1: "...机器学习是人工智能的一个子集，它通过"
chunk2: "它通过数据训练模型来做出预测..."
        ^^^^^^^^ 重叠部分 ^^^^^^^^
```
如果没有 overlap，"它通过"这个指代关系就断了，检索到 chunk2 时 LLM 不知道"它"指什么。

---

## 三、双路存储架构（VectorStoreService + Bm25Service）

### 3.1 为什么同时用向量检索和 BM25？

**单一向量检索的问题**：
- 向量检索擅长**语义匹配**（"怎么提升记忆力" → 匹配"艾宾浩斯遗忘曲线"）
- 但对**精确关键词**匹配较弱（搜"JVM GC"可能匹配到不相关的"Java性能优化"）

**单一 BM25 的问题**：
- BM25 擅长**关键词匹配**（词频+逆文档频率）
- 但不理解语义（搜"怎么记住东西"匹配不到"间隔重复"）

**解决方案**：双路检索，取长补短
```
查询 ──┬── 向量检索（语义相似度）── Top K 结果 ──┐
       └── BM25 检索（关键词匹配）── Top K 结果 ──┤
                                                  ▼
                                            结果融合
```

### 3.2 向量存储（VectorStoreService）

**ChromaDB + 内存降级**：
- 优先连接 ChromaDB（持久化存储）
- 连接失败时降级为 `ConcurrentHashMap` 内存存储
- 降级后功能完整，只是重启丢失数据

**用户隔离**：
- 每个用户的检索都加 `filter: user_id = xxx`
- 确保 A 用户搜不到 B 用户的笔记和知识库

### 3.3 BM25 检索（Bm25Service）

**基于 Lucene 实现**：
- 每个用户独立的内存索引（`ByteBuffersDirectory`）
- 使用 `StandardAnalyzer`（支持中英文分词）
- 查询时自动转义特殊字符，防止 Lucene 查询语法注入

**为什么用内存索引而不是持久化**：
- BM25 只是辅助检索，主存储在 ChromaDB
- 内存索引重启后从向量库重建，简化架构
- 用户量不大时内存完全够用

---

## 四、查询优化 — HyDE（RagService.generateHypotheticalDocument）

### 4.1 什么是 HyDE

**HyDE = Hypothetical Document Embedding（假设性文档嵌入）**

用户的问题通常很短（"什么是遗忘曲线"），而文档是长文本。短问题和长文档在向量空间中的表示可能距离较远。

**HyDE 的做法**：先让 LLM 根据问题生成一个"假设性回答"，再用这个回答去做向量检索。

```
原始问题: "什么是遗忘曲线"
    │
    ▼ LLM 生成假设性文档
假设性文档: "遗忘曲线是德国心理学家艾宾浩斯提出的理论，
           描述了人类记忆随时间衰减的规律。研究表明，
           学习后20分钟遗忘42%，1天后遗忘74%..."
    │
    ▼ 用假设性文档做向量检索
检索结果: [实际的遗忘曲线笔记] ← 语义匹配度更高
```

### 4.2 为什么 HyDE 能提升检索质量

| 方面 | 直接用问题检索 | 用 HyDE 文档检索 |
|------|--------------|----------------|
| 文本长度 | 短（10-20字） | 长（100-200字） |
| 语义密度 | 稀疏 | 密集 |
| 与文档的向量距离 | 较远 | 较近 |
| 匹配精度 | 一般 | 更高 |

**核心原理**：向量模型对长文本的语义编码比短问题更准确。假设性文档虽然可能有幻觉，但它与真实文档在**语义空间**中更接近。

### 4.3 兜底策略

```java
try {
    // 调用 LLM 生成假设性文档
    Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
    return response.content().text();
} catch (Exception e) {
    log.warn("HyDE generation failed, using raw query: {}", e.getMessage());
    return query;  // 失败时退化为原始查询
}
```

**为什么需要兜底**：LLM API 可能超时、限流、网络异常。HyDE 是锦上添花，不是必须的。失败时用原始查询继续检索，保证系统可用性。

---

## 五、双路检索与结果融合（RagService.retrieveDocuments）

### 5.1 检索流程

```java
// 1. 生成假设性文档
String hypotheticalDoc = generateHypotheticalDocument(query);

// 2. 知识库检索（Top K，K 由配置决定）
List<Map<String, Object>> knowledgeResults = vectorStoreService.searchKnowledge(
        userId, hypotheticalDoc, props.getChroma().getK());
knowledgeResults.forEach(r -> r.put("source_type", "knowledge_base"));

// 3. 笔记检索（Top 3）
List<Map<String, Object>> noteResults = vectorStoreService.searchNotes(
        userId, hypotheticalDoc, 3);
noteResults.forEach(r -> r.put("source_type", "note"));

// 4. 融合：笔记优先，知识库其次
List<Map<String, Object>> merged = new ArrayList<>();
merged.addAll(noteResults);
merged.addAll(knowledgeResults);
```

### 5.2 为什么笔记排在知识库前面

**用户笔记是用户自己写的**，与用户的问题意图更相关。知识库是上传的外部文档，相关性可能较低。

在后续的截断处理中（取前3篇），笔记会优先被保留，确保用户自己的知识不被外部文档淹没。

### 5.3 来源标注的作用

每篇文档都会被打上标签：
```
[来源：笔记《遗忘曲线笔记》]
艾宾浩斯遗忘曲线描述了...

[来源：知识库《认知心理学导论》]
记忆分为短期记忆和长期记忆...
```

**为什么需要来源标注**：
1. 让 LLM 回答时可以引用来源（"根据你的笔记..."）
2. 让用户知道答案来自哪里，增加可信度
3. 区分"我写的笔记"和"我上传的文档"，信息权重不同

---

## 六、MapReduce 并发摘要（RagService.getDocumentsAndSummary）

### 6.1 为什么需要并发摘要

**问题**：如果串行调用 LLM 总结 3 篇文档，每篇需要 3-5 秒，总共需要 9-15 秒。用户体验极差。

**解决方案**：`CompletableFuture.supplyAsync` 线程池并发执行

```
文档1 ──→ [线程1] LLM摘要 ──→ 摘要1 ──┐
文档2 ──→ [线程2] LLM摘要 ──→ 摘要2 ──┤──→ 二次融合 ──→ 最终回答
文档3 ──→ [线程3] LLM摘要 ──→ 摘要3 ──┘
```

总耗时 ≈ max(3,5,4) = 5 秒，而不是 3+5+4 = 12 秒。

### 6.2 Map 阶段 — 并发单文档摘要

```java
List<CompletableFuture<String>> summaryFutures = topDocs.stream()
    .map(doc -> CompletableFuture.supplyAsync(() -> {
        // 构造单文档摘要 Prompt
        String prompt = "基于以下问题和文档内容，生成简洁的摘要：\n\n"
                      + "问题：" + query + "\n\n文档：\n" + doc + "\n\n摘要：";
        Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
        return response.content().text();
    }, taskExecutor)  // 使用自定义线程池
    .orTimeout(30, TimeUnit.SECONDS))  // 30秒硬超时
    .collect(Collectors.toList());
```

**关键设计点**：

1. **自定义线程池**（AsyncConfig）：
   - 核心线程 4，最大线程 16，队列容量 100
   - 不用 ForkJoinPool.commonPool()，避免阻塞其他异步任务

2. **30秒硬超时**：
   - LLM API 可能卡住（网络抖动、服务端排队）
   - 超时后 `CompletableFuture` 自动完成（抛出 TimeoutException）
   - 防止线程永久阻塞

3. **降级策略**：
   ```java
   } catch (Exception e) {
       log.warn("Document summarization failed: {}", e.getMessage());
       return doc.substring(0, Math.min(200, doc.length()));  // 截取前200字
   }
   ```
   LLM 调用失败时，直接截取原文前 200 字作为"摘要"。粗暴但有效——至少有内容返回给用户。

### 6.3 Reduce 阶段 — 二次融合

```java
if (summaries.size() > 1) {
    // 用分隔符拼接多篇摘要
    String combinedContext = String.join("\n\n---\n\n", summaries);
    // 构造融合 Prompt
    String mergePrompt = "基于以下多个文档的摘要，生成一个综合性的回答：\n\n"
                       + "问题：" + query + "\n\n文档摘要：\n" + combinedContext
                       + "\n\n综合回答：";
    Response<AiMessage> response = chatModel.generate(UserMessage.from(mergePrompt));
    finalSummary = response.content().text();
} else {
    finalSummary = summaries.get(0);  // 只有一篇，直接返回
}
```

**为什么需要二次融合**：
- 3 篇文档的摘要可能有重复、矛盾、或缺乏连贯性
- 二次融合让 LLM 去重、整合、生成流畅的综合回答
- 这就是 MapReduce 的 Reduce 思想：先分散处理，再汇聚结果

**融合失败的兜底**：
```java
} catch (Exception e) {
    finalSummary = String.join("\n\n", summaries);  // 直接拼接
}
```

---

## 七、Agent 架构（AgentService + AgentTools）

### 7.1 Function Calling 决策机制

**设计决策**：不硬编码"什么问题用 RAG"，而是让 LLM 自己决定是否调用工具。

**传统做法（硬编码）**：
```java
if (query.contains("笔记") || query.contains("知识库")) {
    ragResult = ragService.retrieve(query);
}
```
问题：需要穷举关键词，容易误判或漏判。

**本项目做法（Function Calling）**：
```java
// 把所有工具定义发给 LLM
Response<AiMessage> chatResponse = chatModel.generate(messages, toolSpecifications);

if (aiMessage.hasToolExecutionRequests()) {
    // LLM 决定调用哪些工具
    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
        String toolResult = executeToolWithUserId(toolName, toolArgs, userId);
        messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
    }
    // 继续循环，让 LLM 基于工具结果生成回复
} else {
    // LLM 认为不需要工具，直接返回文本
    return aiMessage.text();
}
```

### 7.2 工具列表

| 工具 | 触发条件 | 功能 |
|------|---------|------|
| `ragSummary` | 用户提到知识库、文档、资料 | RAG 检索 + 摘要 |
| `searchNotes` | 用户要搜索笔记 | 笔记语义搜索 |
| `getNoteStats` | 用户问笔记统计 | 返回笔记总数/分类 |
| `getTodayReviews` | 用户问今天复习什么 | 艾宾浩斯复习列表 |
| `markReviewed` | 用户标记已复习 | 更新复习记录 |
| `createNote` | 用户要创建笔记 | 调用 NoteService |
| `getRelatedNotes` | 用户问相关笔记 | 向量相似度检索 |
| `whatTimeIsNow` | 用户问时间 | 返回当前时间 |

### 7.3 Agent 循环

```
用户问题 + 工具定义 → LLM
    │
    ├─ LLM 返回文本 → 直接输出（结束）
    │
    └─ LLM 返回工具调用 → 执行工具 → 工具结果加入消息列表 → 再次调用 LLM
                                                            │
                                                            ├─ LLM 返回文本 → 输出
                                                            └─ LLM 再次调工具 → 循环...
```

**最大迭代次数**：3 次（可配置），防止无限循环。

### 7.4 主 Prompt 的决策规则（main_prompt.txt）

```
**不需要工具，直接回答的情况：**
- 通用知识问题（如 JVM 原理、算法、编程概念）
- 闲聊、打招呼

**需要使用工具的情况：**
- 用户明确提到"我的笔记"、"我的文档"、"知识库里"
- 用户要搜索、查找、统计自己的笔记
- 用户要创建笔记、回顾笔记
```

**为什么这样设计**：
- 通用知识问题不需要检索用户的私有数据，直接用 LLM 知识回答
- 避免每次回答都触发 RAG 检索，浪费时间和 API 调用
- 让 Agent 更智能，而不是机械地"每次都查库"

---

## 八、SSE 流式输出（AgentService.streamAgentResponse）

### 8.1 为什么用 SSE 而不是普通 HTTP 响应

**普通 HTTP**：等 LLM 完全生成完才返回，用户盯着空白页面等 10-30 秒。

**SSE（Server-Sent Events）**：边生成边推送，用户实时看到思考过程和回复。

### 8.2 事件类型

```json
{"type": "thinking", "stage": "tool_call", "content": "正在调用工具: ragSummary"}
{"type": "thinking", "stage": "complete", "content": "已处理完成"}
{"type": "response", "content": "根据你的笔记...", "session_id": "xxx"}
{"type": "done", "session_id": "xxx"}
{"type": "error", "content": "处理请求时发生错误: ..."}
```

### 8.3 流式分块输出

```java
int chunkSize = 50;
for (int i = 0; i < response.length(); i += chunkSize) {
    int end = Math.min(i + chunkSize, response.length());
    sendSseEvent(emitter, "response", Map.of(
            "content", response.substring(i, end),
            "session_id", sessionId
    ));
    Thread.sleep(50);  // 控制推送速度
}
```

**为什么分块推送**：一次性推送整个回复会导致前端一次性渲染大量文本，造成页面卡顿。分块推送配合前端的打字机效果，体验更流畅。

### 8.4 SecurityContext 传递

```java
SecurityContext securityContext = SecurityContextHolder.getContext();
CompletableFuture.runAsync(() -> {
    SecurityContextHolder.setContext(securityContext);
    try {
        // ... 异步处理
    } finally {
        SecurityContextHolder.clearContext();
    }
}, taskExecutor);
```

**为什么需要手动传递**：SSE 请求在独立线程中处理，Spring Security 的 `SecurityContext` 默认不跨线程传递。如果不手动设置，异步线程中拿不到用户身份，导致权限校验失败。

**配合 `MODE_INHERITABLETHREADLOCAL`**：
```java
@Bean
public MethodInvokingFactoryBean securityContextHolderStrategy() {
    bean.setArguments(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
}
```
让子线程自动继承父线程的 SecurityContext，双重保障。

---

## 九、线程池配置（AsyncConfig）

```java
executor.setCorePoolSize(4);      // 核心线程：常驻
executor.setMaxPoolSize(16);      // 最大线程：突发负载时扩容
executor.setQueueCapacity(100);   // 队列：超出核心线程的任务排队
executor.setThreadNamePrefix("async-");  // 线程名前缀：方便排查问题
```

**为什么这样配置**：
- 核心 4 线程：日常请求量不需要太多线程
- 最大 16 线程：多文档并发摘要时需要更多线程
- 队列 100：缓冲突发请求，避免拒绝任务
- 线程名前缀：日志中看到 `async-1`、`async-2` 就知道是异步任务

---

## 十、设计中遇到的问题与解决方案

| 问题 | 解决方案 |
|------|---------|
| 中文切片会切断句子 | 按中文标点层级切分，而非固定字符数 |
| 短问题与长文档向量距离远 | HyDE：先生成假设性文档再检索 |
| 纯向量检索漏掉精确关键词 | BM25 + 向量双路检索 |
| 串行摘要耗时太长 | CompletableFuture 并发 + 30s 超时 |
| LLM API 超时/限流 | 每个环节都有兜底（原始查询/原文截取/直接拼接） |
| 重启后向量数据丢失 | MD5 + 向量数据双重校验，缺失时自动重建 |
| 用户数据隔离 | 检索时加 user_id 过滤 + BM25 每用户独立索引 |
| 异步线程丢失认证信息 | SecurityContext 手动传递 + INHERITABLETHREADLOCAL |
| Agent 每次都查库浪费资源 | main_prompt 定义决策规则，LLM 按需调用工具 |
| 多文档摘要重复/矛盾 | Reduce 阶段二次融合，LLM 去重整合 |
