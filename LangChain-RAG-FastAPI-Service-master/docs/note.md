# RAG-Note 项目架构与开发思路梳理

> AI 驱动的智能知识管理笔记系统，基于 RAG（检索增强生成）+ 间隔复习 + Agent 智能体
> 
写在前面：

    chroma启动：
        conda activate chroma-server
        chroma run --host 0.0.0.0 --port 8001 --path D:/code/javacode/RAG-Note/data/chromadb
    前端启动：
        cd LangChain-RAG-FastAPI-Service-master/front
        npm run dev

---

## 一、项目总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (Vue 3 + Vite)                       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ AIChat   │ │ NoteList │ │Knowledge │ │DailyReview│  ...      │
│  │ SSE流式  │ │ 笔记列表 │ │ 知识库    │ │ 间隔复习  │           │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘           │
│       │            │            │            │                   │
│  ┌────┴────────────┴────────────┴────────────┴──────┐           │
│  │           Pinia Store (user/session/theme)        │           │
│  └──────────────────────┬───────────────────────────┘           │
│                         │ fetch / axios                          │
└─────────────────────────┼───────────────────────────────────────┘
                          │ HTTP / SSE
┌─────────────────────────┼───────────────────────────────────────┐
│                   后端 (Spring Boot 3.4.1)                       │
│  ┌──────────────────────┴───────────────────────────┐           │
│  │          SecurityConfig + JWT Filter              │           │
│  └──────────────────────┬───────────────────────────┘           │
│  ┌──────────────────────┴───────────────────────────┐           │
│  │              Controller Layer (REST API)          │           │
│  │  ChatController │ NoteController │ KnowledgeCtrl  │           │
│  │  ReviewController │ UserController │ HealthCtrl   │           │
│  │  EvaluationController                              │           │
│  └──────────────────────┬───────────────────────────┘           │
│  ┌──────────────────────┴───────────────────────────┐           │
│  │               Service Layer (业务逻辑)            │           │
│  │  AgentService │ RagService │ NoteService          │           │
│  │  SupervisorService │ WriterService │ ClarifierSvc │           │
│  │  ChatService │ KnowledgeService │ ReviewService   │           │
│  │  EvaluationService │ QualityReviewer              │           │
│  └──────┬────────────┬────────────┬─────────────────┘           │
│  ┌──────┴──────┐ ┌───┴────┐ ┌────┴──────────────┐              │
│  │ LangChain4j │ │  JPA   │ │  Redis Cache      │              │
│  │ (LLM调用)   │ │ (MySQL)│ │  (Token黑名单等)  │              │
│  └─────────────┘ └────────┘ └───────────────────┘              │
│  ┌─────────────────────────────────────────────────┐           │
│  │  VectorStoreService (MySQL + ChromaDB + BM25)     │           │
│  │  DocumentProcessor (Tika文档解析 + 分块)         │           │
│  │  Md5Store (文件指纹去重)                         │           │
│  └─────────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、技术栈明细

| 层次         | 技术选型                          | 版本    | 用途                          |
|-------------|----------------------------------|---------|-------------------------------|
| 后端框架     | Spring Boot                      | 3.4.1   | 应用框架                      |
| 语言         | Java                             | 17      | LTS 版本                      |
| AI/LLM      | LangChain4j                      | 0.35.0  | LLM 调用、Embedding、Agent    |
| LLM 供应商   | 智谱GLM / Ollama / 通义千问       | -       | 多模型可切换                   |
| 数据库       | MySQL + JPA/Hibernate            | -       | 持久化存储                     |
| 缓存         | Redis                            | -       | Token 黑名单、用户缓存         |
| 安全         | Spring Security + JWT (jjwt)     | 0.12.6  | 认证授权                      |
| 文档解析     | Apache Tika                      | 2.9.2   | 多格式文件内容提取              |
| 全文检索     | Apache Lucene                    | 9.12.1  | BM25 关键词检索                |
| 向量存储     | ChromaDB（LangChain4j 接入）     | -       | 向量相似度检索                  |
| 前端框架     | Vue 3 + Vite                     | 7.x     | SPA 应用                      |
| UI 组件      | Vant 4                           | 4.9.21  | 移动端 UI 组件库               |
| 状态管理     | Pinia                            | 3.0.3   | 全局状态 (含持久化)             |
| Markdown    | ByteMD + marked + highlight.js   | -       | 笔记编辑 & 渲染                |
| 国际化       | vue-i18n                         | 9.8.0   | 中英文切换                     |

---

## 三、后端模块详细设计

### 3.1 启动入口 `RagNotebookApplication.java`

```java
@SpringBootApplication
@EnableAsync  // 启用异步方法支持，用于笔记自动标签、文档处理等后台任务
public class RagNotebookApplication { ... }
```

- `@EnableAsync` 是关键注解：笔记创建后的 LLM 自动标签、文档上传后的向量化处理都依赖异步执行，避免阻塞主线程。

---

### 3.2 Agent 智能体模块 (`agent/`)

这是整个系统的"大脑"，采用**多 Agent 流水线架构**，负责理解用户意图、规划任务、调度工具、合成回答。

#### 3.2.1 `ModelFactory.java` — 模型工厂

**设计模式**：工厂模式，统一封装 LLM 的创建逻辑，支持三种温度预设：

```
createChatModel()        → 默认温度 0.7
createPreciseModel()     → 温度 0.1（工具选择、质量审查、任务规划）
createBalancedModel()    → 温度 0.5（RAG 总结、一般对话）
createCreativeModel()    → 温度 0.8（Query 扩展、写作、笔记生成）
createEmbeddingModel()   → Embedding 模型
createVisionModel()      → 视觉模型
```

**实现细节**：
- 智谱 GLM：通过 `OpenAiChatModel` 适配（智谱提供 OpenAI 兼容 API），设置 `baseUrl` 指向 `open.bigmodel.cn`
- Ollama 本地模型：使用 `OllamaChatModel`，连接本地 `localhost:11434`
- 通义千问：使用 `QwenChatModel`（LangChain4j 原生支持阿里 DashScope）

#### 3.2.2 `AgentService.java` — Agent 核心服务

**核心职责**：接收用户消息，通过 Supervisor 规划决定走单 Agent 还是多 Agent 流水线，最终以 SSE 流式返回结果。

**`streamAgentResponse()` 方法流程**：

```
1. 创建 SseEmitter（300秒超时，多 Agent 可能耗时较长）
2. 捕获当前线程的 SecurityContext（解决异步线程认证丢失问题）
3. 提交到异步线程池执行：
   a. 恢复 SecurityContext
   b. 先保存用户消息（确保即使后续超时，消息也在数据库里）
   c. 加载会话历史，ContextManager 三层压缩
   d. Supervisor 规划：判断查询复杂度
   e. 简单查询 → 单 Agent + Function Calling 循环
   f. 复杂查询 → 多 Agent 流水线（并行子任务 + Writer 合成）
   g. 保存 AI 回复
   h. 计算 token 用量，发送 done 事件（含 trace_id、token_used 等）
   i. 如果没有 RAG trace，保存基础 trace（用于用户反馈）
   j. finally 清除 SecurityContext
4. 立即返回 emitter（释放 Tomcat 线程）
```

**多 Agent 流水线架构**：

```
用户查询
  │
  ▼
SupervisorService.plan() — LLM 分析查询复杂度
  │
  ├─ 子任务数 < 2 → 单 Agent 流程
  │   └─ processWithFunctionCalling()：Function Calling 循环（最多 3 轮）
  │       → 工具调用 → 结果 → 质量审查 → 返回回答
  │
  └─ 子任务数 >= 2 → 多 Agent 流水线
      ├─ 1. 并行执行各子任务（每个子任务独立 Agent 循环，最多 2 轮）
      ├─ 2. WriterService.synthesize() — 合并去重，生成结构化回答
      └─ 3. QualityReviewer.reviewAnswer() — 质量审查，不达标则带反馈重试
```

**SecurityContext 传播**：SSE 在异步线程中执行，而 Spring Security 的认证信息默认只在请求线程中有效（ThreadLocal 线程私有）。解决方案：

```java
// SecurityConfig 中设置策略为可继承
SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL);

// AgentService 中手动捕获并恢复
SecurityContext securityContext = SecurityContextHolder.getContext();
CompletableFuture.runAsync(() -> {
    SecurityContextHolder.setContext(securityContext);
    try {
        // ... 业务逻辑
    } finally {
        SecurityContextHolder.clearContext(); // 防止线程复用时数据泄漏
    }
}, taskExecutor);
```

#### 3.2.3 `SupervisorService.java` — 任务规划 Agent

**职责**：分析用户查询复杂度，决定是否需要拆分为多个子任务。

**流程**：
1. 加载 `prompt/supervisor_plan.txt` 模板
2. 用精确模型（temperature=0.1）分析查询
3. 返回 JSON 数组：`[{"label":"子任务名","description":"描述","tool":"建议工具"}]`
4. 空数组 → 走单 Agent；非空 → 走多 Agent 流水线

#### 3.2.4 `WriterService.java` — 合成 Agent

**职责**：将多个子任务的结果合并为一个连贯、完整的回答。

**流程**：
1. 加载 `prompt/writer_synthesize.txt` 模板
2. 用平衡模型（temperature=0.5）整合各子任务结果
3. 去重、去矛盾、结构化输出
4. 失败时兜底：直接拼接各子任务结果

#### 3.2.5 `ClarifierService.java` — 查询澄清 Agent

**职责**：判断用户查询是否模糊，模糊时返回引导方向。

**判断标准**：
- **清晰**：明确的笔记操作、具体的知识问题、明确引用了自己的笔记/文档
- **模糊**：主题过于宽泛（"帮我学习AI"）、意图不明确（"这个怎么样"）、缺少具体范围

**输出格式**：
```json
// 清晰
{"is_clear": true, "research_brief": "精炼后的查询"}
// 模糊
{"is_clear": false, "message": "引导语", "suggested_directions": ["方向1", "方向2", "方向3"]}
```

#### 3.2.6 `TokenCounter.java` — Token 计数器

**用途**：估算消息列表的 token 数量，控制上下文窗口预算。

**估算策略**（混合）：
- 中文字符：约 1.5 token/字
- 英文单词：约 1.3 token/word（按 4 字符 ≈ 1.3 token 换算）
- 标点/空格：约 0.3 token/个
- 每条消息固定开销：4 token
- 工具调用消息额外开销：20 token

#### 3.2.7 `ContextManager.java` — 上下文管理器

**三层压缩策略**（当 token 超过 32000 预算时触发）：

```
策略1: 工具结果压缩
  找到前 70% 消息中超过 500 字符的 AI 回复
  → 用 LLM 压缩为 300 字符以内的关键摘要
  → 标记为 "[已压缩] ..."

策略2: 摘要压缩（策略1 仍超预算时）
  从后往前累加 token，找到截断点
  → 前 N 条消息用 LLM 压缩为一段摘要
  → 作为 SystemMessage 放在最前面
  → 保留最近消息原文

策略3: 至少保留最近 5 条消息原文
```

#### 3.2.8 `AgentTools.java` — 工具集

Agent 可调用的工具集合，每个方法通过 `@Tool` 注解声明工具名称和描述，LangChain4j 自动提取为工具定义发送给 LLM：

| 工具方法            | @Tool 描述                        | 功能                     |
|-------------------|----------------------------------|--------------------------|
| `ragSummary()`    | 基于RAG检索知识库并生成摘要          | RAG 检索 + 生成摘要       |
| `searchNotes()`   | 搜索用户的笔记                     | 搜索用户笔记              |
| `getNoteStats()`  | 获取笔记统计信息                    | 获取笔记统计信息           |
| `getTodayReviews()`| 获取今日待复习笔记                 | 获取今日待复习笔记         |
| `markReviewed()`  | 标记笔记已回顾                     | 标记笔记已复习             |
| `createNote()`    | 创建新笔记                        | 创建笔记                  |
| `getRelatedNotes()`| 获取相关笔记推荐                  | 获取相关笔记               |
| `whatTimeIsNow()` | 获取当前时间                      | 获取当前时间               |

---

### 3.3 RAG 核心引擎 (`rag/`)

这是系统的知识检索核心，实现从文档到答案的完整链路。

#### 3.3.1 `RagService.java` — RAG 服务

**核心方法 `getDocumentsAndSummary()` 的完整流程**：

```
步骤1: 混合检索
  ├─ 知识库检索：QueryExpander 多 Query 扩展 → 向量检索 + BM25 → RRF 融合 → Cross-Encoder 精排
  └─ 笔记检索：同样流程
  合并结果：笔记优先，知识库其次

步骤2: 构建参考资料（带来源标注）
  每条文档前加 [来源：笔记《xxx》] 或 [来源：知识库《xxx》]

步骤3: 调用 LLM 生成回答
  SystemPrompt（规则：只基于资料回答、不编造、引用来源）
  + UserPrompt（参考资料 + 用户问题）
  → 单次 LLM 调用生成最终回答

步骤4: Trace 记录
  保存检索延迟、生成延迟、平均相似度、文档数、最终回答等
  返回 {documents: [...], summary: "..."}
```

**注意**：RagService 本身是简单的"检索 → 生成"流程。质量审查、HyDE、Map-Reduce 等高级特性在 AgentService 层面实现（Agent 调用 ragSummary 工具后，会对回答做质量审查）。

#### 3.3.2 `HybridRetriever.java` — 混合检索服务

**检索流程**：

```
原始查询
  │
  ├─ QueryExpander.expand() → 3 个扩展查询
  │
  ├─ 路1: 扩展查询1 → 向量检索
  ├─ 路2: 扩展查询1 → BM25 检索
  ├─ 路3: 扩展查询2 → 向量检索
  ├─ 路4: 扩展查询2 → BM25 检索
  │
  └─ RRF (Reciprocal Rank Fusion) 融合
      → score(d) = Σ 1/(k + rank_i(d))，k=60
      → 按分数降序排列
      → Cross-Encoder 精排（RerankerService）
```

#### 3.3.3 `QueryExpander.java` — 多 Query 扩展

将用户原始查询改写为 3 个语义等价但表述不同的版本，用于多路检索提升召回率。

```java
// 使用创意模型（temperature=0.8）生成多样化改写
// 原始查询始终包含，最多 1+3=4 个查询版本
// 每个版本限制 50 字以内
```

**改写策略**：
- 抽象概念展开为具体内容维度（"简历" → "教育背景、工作经历、技能清单"）
- 使用同义词、缩写、全称等不同表达
- 包含具体关键词，避免抽象词汇

#### 3.3.4 `RerankerService.java` — Cross-Encoder 精排服务

使用智谱 AI 的 rerank API 对检索结果进行重排序。

**动态 top-N 策略**：
```
分数 > 0.7 → 全部保留（高质量结果）
分数 0.5-0.7 → 最多保留 2 条（中等质量）
分数 < 0.5 → 丢弃
```

#### 3.3.5 `QualityReviewer.java` — 质量审查服务

参考 sage-research 的 Supervisor Review 机制，提供检索和回答两个阶段的质量审查能力。

**注意**：当前 QualityReviewer 在 **AgentService** 中使用（Agent 回答质量审查），而非 RagService 中。

**审查方法**：
| 方法 | 审查内容 | 使用位置 | 不达标处理 |
|------|---------|---------|-----------|
| `reviewRetrieval()` | 检索文档与查询的相关性 | Agent 层 | 调用 `rewriteQuery()` 改写查询 |
| `reviewAnswer()` | 回答的忠实度和完整性 | AgentService | 带反馈重新生成回答 |
| `rewriteQuery()` | 根据审查反馈改写查询 | Agent 层 | 用精确模型生成改进查询 |

#### 3.3.6 `VectorStoreService.java` — 向量存储服务

**当前实现**：MySQL + ChromaDB + BM25 三路存储，ChromaDB 为向量主存储。

**存储架构**：
```
文档上传/笔记创建
  │
  ├─ MySQL：存储文档元数据 + 切片内容（KnowledgeDocument、KnowledgeDocumentChunk、NoteChunk）
  ├─ ChromaDB：存储向量 + 元数据（通过 LangChain4j ChromaEmbeddingStore）
  └─ BM25：存储关键词索引（Lucene 内存索引）
```

**ChromaDB 连接方式**：
```java
this.noteStore = ChromaEmbeddingStore.builder()
        .baseUrl(chromaUrl)  // http://localhost:8001
        .collectionName(props.getChroma().getNotesCollection())
        .build();
this.knowledgeStore = ChromaEmbeddingStore.builder()
        .baseUrl(chromaUrl)
        .collectionName(props.getChroma().getCollection())
        .build();
```

**降级策略**：ChromaDB 不可用时，标记 `chromaAvailable=false`，知识库检索降级为 MySQL + 内存余弦相似度计算；笔记检索不可用（需 ChromaDB）。

**关键操作**：
- `addNoteVector(note)` — 笔记切片 → MySQL + ChromaDB + BM25 三路写入
- `addKnowledgeDocument(...)` — 知识库文档切片 → MySQL + 异步写入 ChromaDB + BM25
- `searchKnowledge(userId, query, topK)` — ChromaDB 向量检索（降级时用 MySQL + 余弦相似度）
- `searchNotes(userId, query, topK)` — ChromaDB 向量检索（需 ChromaDB 可用）
- `deleteKnowledgeByFilename(...)` — 删除 MySQL + ChromaDB + BM25 三路数据
- `getUserDocuments(userId)` — 从 MySQL 获取文档列表
- `getDocumentDetail(userId, filename)` — 从 MySQL 获取文档详情（所有切片合并）

**ChromaDB Collection ID 缓存**：Collection ID 存入 Redis（TTL 24 小时），避免每次删除操作都查询 ChromaDB。

#### 3.3.7 `DocumentProcessor.java` — 文档处理器

**处理流程**：

```
文件上传 → MD5 去重检查 → Tika 解析提取文本 → 中文感知分块 → 存入向量库
```

**中文分块算法细节**：

```java
// 分隔符优先级：段落 > 句号 > 感叹号 > 问号 > 分号 > 逗号
String[] separators = {"\n\n", "\n", "。", "！", "？", ".", "!", "?", "；", ";", "，", ","};
```

分块策略：
1. 按优先级尝试分隔符，找到第一个能产生多个片段的分隔符
2. 逐片段累加，当当前块长度超过 `chunkSize`（默认200字）时，切出一个新块
3. 新块保留前一个块末尾的 `chunkOverlap`（默认20字）作为上下文重叠
4. 这种重叠机制确保语义不会在块边界处断裂

**文件名前缀**：每个切片前加 `[文件: xxx.pdf]` 前缀，提升关键词检索命中率（用户搜索文件名时能匹配到所有切片）。

**MD5 去重**：同一个文件不会重复处理，通过文件内容的 MD5 哈希判断。MD5 记录存在但向量数据丢失时，会清理旧记录重新处理。

---

### 3.4 聊天模块 (`chat/`)

#### 3.4.1 `ChatController.java` — 聊天 API

| 端点                                     | 方法   | 功能                     |
|-----------------------------------------|--------|--------------------------|
| `POST /chat/agent/query/stream`         | POST   | Agent 流式查询（SSE，多 Agent 流水线） |
| `POST /chat/rag/query`                  | POST   | RAG 直接查询（非流式）    |
| `POST /chat/clarify`                    | POST   | 查询澄清（判断模糊查询）  |
| `GET /chat/session/{sessionId}`         | GET    | 获取会话历史              |
| `DELETE /chat/session/{sessionId}`      | DELETE | 删除会话                  |
| `GET /chat/sessions`                    | GET    | 获取所有会话列表          |
| `GET /chat/sessions/{userId}`           | GET    | 获取指定用户的会话        |
| `POST /chat/reorder`                    | POST   | 文档重排序                |

**SSE 流式响应设计**：`/chat/agent/query/stream` 返回 `SseEmitter`，前端通过 `EventSource` 或 `fetch` + `ReadableStream` 读取。事件类型：

```json
{"type": "thinking", "stage": "planning", "content": "已拆分为 2 个子任务，正在并行执行"}
{"type": "thinking", "stage": "researching", "content": "[R-1] 检索知识库 执行中"}
{"type": "thinking", "stage": "tool_call", "content": "正在调用工具: ragSummary"}
{"type": "thinking", "stage": "writing", "content": "正在整合 2 个子任务结果"}
{"type": "thinking", "stage": "review", "content": "回答质量不足，正在优化: ..."}
{"type": "thinking", "stage": "complete", "content": "已处理完成"}
{"type": "response", "content": "回答片段...", "session_id": "xxx"}
{"type": "done", "session_id": "xxx", "trace_id": "xxx", "token_used": 1234, "token_max": 32000}
{"type": "error", "content": "错误信息"}
```

#### 3.4.2 `ChatService.java` — 聊天服务

封装会话管理和消息存储逻辑：
- `addMessage()` — 保存消息到数据库
- `getSessionHistory()` — 加载会话历史，按 human/ai 配对组装
- `ragQuery()` — 委托给 RagService 执行 RAG 检索

#### 3.4.3 数据模型

**ChatSession**：会话实体，关联用户
**ChatMessage**：消息实体，包含 role（human/ai）、content、metadata（JSON 类型），与 ChatSession 多对一关联

---

### 3.5 笔记模块 (`note/`)

#### 3.5.1 `NoteService.java` — 笔记服务

**笔记创建的完整流程**：

```
1. 创建 Note 实体，保存到 MySQL
2. 同步添加向量索引（VectorStoreService.addNoteVector）
3. 事务提交后，异步执行：
   a. 调用 LLM 自动生成分类和标签
   b. 创建复习记录（ReviewRecord）
```

**LLM 自动标签实现细节**：

```java
// Prompt 模板
String prompt = "请根据以下笔记内容，返回一个JSON格式的分类结果。\n" +
    "要求：\n" +
    "1. category：从 [work, study, life, project] 中选一个\n" +
    "2. tags：返回2-4个标签关键词\n" +
    "只返回JSON，不要其他文字。格式：{\"category\":\"work\",\"tags\":[\"xxx\",\"xxx\"]}";

// 解析逻辑：手动从 LLM 返回的文本中提取 JSON 字段
private String parseCategory(String llmResponse) {
    int start = llmResponse.indexOf("\"category\"");
    // ... 字符串解析
}
```

**为什么用字符串解析而不是 JSON 库**：LLM 返回的内容不一定是标准 JSON，可能包含额外文字，手动解析更健壮。

**事务与异步的协调**：

```java
// 使用 TransactionSynchronization 确保异步任务在事务提交后执行
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        self.asyncAutoTagAndReview(noteId, userId, category);
    }
});
```

这是因为 `@Async` 方法在另一个线程执行，如果事务还没提交，异步线程查不到刚创建的笔记。

#### 3.5.2 `Note.java` — 笔记实体

```java
@Entity
@Table(name = "notes")
public class Note {
    @Id @Column(length = 36)
    private String id;           // UUID 主键

    @Column(nullable = false)
    private String userId;       // 所属用户

    @Column(length = 200, nullable = false)
    private String title;        // 标题

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;      // 内容（TEXT 类型，支持大段文本）

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<String> tags;   // 标签（JSON 数组，Hibernate 6 原生支持）

    @Column(length = 50)
    private String category;     // 分类：work/study/life/project

    @CreationTimestamp
    private LocalDateTime createdAt;   // 自动填充创建时间

    @UpdateTimestamp
    private LocalDateTime updatedAt;   // 自动填充更新时间
}
```

#### 3.5.3 `NoteController.java` — 笔记 API

| 端点                              | 方法   | 功能           |
|----------------------------------|--------|----------------|
| `POST /note/create`              | POST   | 创建笔记        |
| `GET /note/list`                 | GET    | 笔记列表（分页）|
| `GET /note/{noteId}`             | GET    | 笔记详情        |
| `PUT /note/{noteId}`             | PUT    | 更新笔记        |
| `DELETE /note/{noteId}`          | DELETE | 删除笔记        |
| `GET /note/search`               | GET    | 搜索笔记        |
| `GET /note/{noteId}/related`     | GET    | 获取相关笔记    |
| `POST /note/assist/stream`       | POST   | AI 辅助写作（SSE）|
| `POST /note/autocomplete`        | POST   | 行内自动补全    |

---

### 3.6 知识库模块 (`knowledge/`)

#### 3.6.1 `KnowledgeService.java` — 知识库服务

**文件上传流程**：

```
MultipartFile → 写入临时文件 → DocumentProcessor.processFile()
→ Tika 解析 → MD5 去重 → 分块 → 向量化存储 → 删除临时文件
```

**SSE 流式上传**：`uploadMultipleStream()` 支持多文件上传时实时推送进度：

```java
// SSE 事件格式
{"stage": "loading", "file": "xxx.pdf", "data": "xxx.pdf"}
{"stage": "splitting", "file": "xxx.pdf", "data": "xxx.pdf"}
{"stage": "storing", "file": "xxx.pdf", "data": "xxx.pdf"}
{"stage": "completed", "file": "xxx.pdf", "data": "xxx.pdf"}
{"stage": "all_completed"}
```

**MD5 去重机制**：`Md5Store` 存储文件的 MD5 哈希值，上传时先检查是否已存在，避免重复处理。

---

### 3.7 间隔复习模块 (`review/`)

#### 3.7.1 `ReviewService.java` — 复习服务

**艾宾浩斯遗忘曲线实现**：

```java
// 复习间隔序列（天）
private static final int[] INTERVALS = {1, 2, 4, 7, 15, 30};
```

**`markReviewed()` 方法逻辑**：
1. 查找用户的复习记录
2. 复习次数 +1
3. 根据复习次数查表获取下一次间隔天数
4. 更新 `nextReviewAt = now + intervalDays`
5. 保存记录

**`getTodayReviews()` 方法**：查询 `nextReviewAt <= now` 的所有复习记录，关联查询笔记详情返回给前端。

**自动生成复习记录**：笔记创建时，`NoteService.asyncAutoTagAndReview()` 会自动创建一条复习记录，`nextReviewAt` 设为明天，`intervalDays` 设为 1。

---

### 3.8 评估模块 (`evaluation/`)

RAG 质量评估系统，支持四指标 LLM 评估 + 规则评估 + 自动化定时任务。

#### 3.8.1 `EvaluationService.java` — 评估服务

**四指标 LLM 评估**（每个指标 0-10 分，归一化为 0-1）：

| 指标 | 评估内容 | 低分诊断 |
|------|---------|---------|
| Context Precision | 检索文档与问题的相关性 | 需要优化 Reranker 或 TopK |
| Context Recall | 检索文档是否覆盖回答所需信息 | 需要优化 Query 扩展或降低 TopK 阈值 |
| Faithfulness | 回答是否基于检索文档（有无幻觉） | 需要优化 Prompt 约束或更换模型 |
| Answer Relevancy | 回答是否回应了用户问题 | 大概率是检索文档不对 |

**规则评估**（硬指标扣分）：
- 耗时 > 15秒：扣 20 分；> 10秒：扣 15 分；> 5秒：扣 8 分
- 检索为空：扣 30 分
- 回答 < 20字：扣 15 分
- 平均相似度 < 0.3：扣 15 分

**综合评分**：四指标 70% + 规则 30%，评级：优秀(≥90) / 良好(≥75) / 及格(≥60) / 不及格

**自动诊断**：根据四指标组合反推问题出在哪（Embedding、Chunk、Reranker、Prompt 等）。

#### 3.8.2 `BatchEvaluationService.java` — 批量评估服务

| 定时任务 | 时间 | 功能 |
|---------|------|------|
| `dailyEvaluation()` | 每天凌晨 2 点 | 自动评估前一天的所有 Trace |
| `weeklyReport()` | 每周一凌晨 3 点 | 输出周报统计（平均分、各指标、低分率） |

#### 3.8.3 `EvaluationController.java` — 评估 API

| 端点 | 方法 | 功能 |
|------|------|------|
| `POST /evaluation/feedback` | POST | 用户反馈评分（1-5 分） |
| `GET /evaluation/report/{traceId}` | GET | 查看单条评估报告 |
| `GET /evaluation/reports` | GET | 批量查看评估报告（分页） |
| `POST /evaluation/batch` | POST | 手动触发批量评估 |
| `GET /evaluation/stats` | GET | 查看整体统计（今日/本周） |
| `GET /evaluation/low-scores` | GET | 获取低分样本列表 |

#### 3.8.4 `RagTrace.java` — RAG 追踪实体

记录每次 RAG 调用的全链路数据：
- 检索延迟、生成延迟、总延迟
- 检索文档数、平均相似度
- 检索文档预览、最终回答
- 用户反馈评分和原因

---

### 3.9 用户认证模块 (`auth/` + `user/`)

#### 3.8.1 认证流程

```
登录流程：
  POST /user/login → AuthService.login()
  → 验证用户名/邮箱 → 验证密码(BCrypt)
  → 生成 JWT Token → 返回 {user, token}

请求认证流程：
  请求 → JwtAuthenticationFilter
  → 从 Header 提取 Bearer Token
  → JwtService 解析 Token（验证签名、检查黑名单）
  → 设置 SecurityContext
  → UserIdArgumentResolver 从 SecurityContext 提取 userId 注入 Controller 参数
```

#### 3.8.2 `JwtService.java` — JWT 服务

- **Token 生成**：包含 userId、username、email，使用 HS256 签名，有效期 24 小时
- **Token 黑名单**：登出时将 Token 的 JTI（唯一标识）存入 Redis，设置 TTL 等于 Token 剩余有效期
- **Token 刷新**：旧 Token 加入黑名单，生成新 Token

#### 3.8.3 `@UserId` 注解

自定义参数注解，配合 `UserIdArgumentResolver`，Controller 方法中直接注入当前登录用户的 ID：

```java
@GetMapping("/note/list")
public ApiResponse<NoteListResponse> listNotes(@UserId String userId, ...) {
    // userId 自动从 JWT 中提取
}
```

---

### 3.10 配置模块 (`config/`)

#### 3.9.1 `ApplicationProperties.java`

统一配置属性类，使用 `@ConfigurationProperties(prefix = "app")` 绑定 `application.yml` 中的配置：

```
app.jwt.*          — JWT 配置（密钥、算法、过期时间）
app.llm.*          — LLM 配置（类型、各供应商的 API Key/模型）
app.embed.*        — Embedding 模型配置
app.vision.*       — 视觉模型配置
app.chroma.*       — 向量存储配置（URL、集合名、分块参数）
app.reranker.*     — 重排序模型配置
app.md5.*          — MD5 存储目录
app.rate-limit.*   — 限流配置
```

#### 3.9.2 `SecurityConfig.java`

```java
http.csrf(AbstractHttpConfigurer::disable)           // 前后端分离，禁用 CSRF
    .sessionManagement(STATELESS)                     // 无状态，JWT 认证
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/health/**").permitAll()     // 健康检查公开
        .requestMatchers("/user/login", "/user/register").permitAll()  // 登录注册公开
        .anyRequest().authenticated()                 // 其余需认证
    )
    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
```

#### 3.9.3 `AsyncConfig.java`

配置异步线程池，用于笔记自动标签、文档处理、Agent 流式响应等后台任务。

---

## 四、前端模块详细设计

### 4.1 项目结构

```
front/src/
├── App.vue                 # 根组件，路由视图 + keepAlive 缓存
├── main.js                 # 入口，注册 Vant 组件、Pinia、Router、i18n
├── style.css               # 全局样式（CSS 变量、主题系统）
│
├── config/
│   └── api.js              # API 端点配置集中管理
│
├── router/
│   └── index.js            # 路由配置 + 全局前置守卫
│
├── store/                  # Pinia 状态管理
│   ├── index.js            # Pinia 初始化（含持久化插件）
│   ├── user.js             # 用户状态（登录/注册/信息）
│   ├── session.js          # 聊天会话状态
│   ├── theme.js            # 主题切换（亮/暗）
│   └── language.js         # 语言切换
│
├── views/                  # 页面组件
│   ├── Login.vue           # 登录页
│   ├── Register.vue        # 注册页
│   ├── AIChat.vue          # AI 对话页（SSE 流式）
│   ├── NoteList.vue        # 笔记列表页
│   ├── NoteEditor.vue      # 笔记编辑器（双栏布局）
│   ├── KnowledgeBase.vue   # 知识库管理页
│   ├── DailyReview.vue     # 每日回顾页
│   ├── Sessions.vue        # 会话管理页
│   ├── Profile.vue         # 个人信息页
│   ├── Settings.vue        # 设置页
│   └── AboutUs.vue         # 关于页面
│
├── components/             # 通用组件
│   ├── TabBar.vue          # 底部导航栏
│   ├── MarkdownEditor.vue  # Markdown 编辑器（基于 ByteMD）
│   ├── QuickToolbar.vue    # Markdown 快捷工具栏
│   ├── TagBadge.vue        # 标签徽章
│   ├── ReviewCard.vue      # 复习卡片
│   ├── RelatedNotes.vue    # 相关笔记推荐
│   └── InlineCompletion.vue # 行内 AI 补全
│
├── composables/
│   └── useAuthImage.js     # 图片鉴权 Hook
│
├── utils/
│   └── csrf.js             # CSRF Token 工具
│
└── i18n/                   # 国际化
    ├── index.js
    └── locales/
        ├── zh-CN.js
        └── en-US.js
```

### 4.2 路由设计与导航守卫

**路由表**：

| 路径                  | 页面          | keepAlive | 说明           |
|----------------------|---------------|-----------|----------------|
| `/`                  | -             | -         | 重定向到 /notes |
| `/login`             | Login         | false     | 登录页          |
| `/register`          | Register      | false     | 注册页          |
| `/chat`              | AIChat        | true      | AI 对话         |
| `/chat/:sessionId`   | AIChat        | true      | 指定会话对话     |
| `/notes`             | NoteList      | true      | 笔记列表        |
| `/notes/:id`         | NoteEditor    | false     | 笔记编辑器      |
| `/knowledge`         | KnowledgeBase | false     | 知识库管理       |
| `/review`            | DailyReview   | false     | 每日回顾        |
| `/sessions`          | Sessions      | true      | 会话管理        |
| `/my`                | My            | true      | 个人中心        |
| `/profile`           | Profile       | false     | 个人信息        |
| `/settings`          | Settings      | false     | 设置页          |

**全局前置守卫**：

```javascript
router.beforeEach((to, from, next) => {
    document.title = to.meta.title || 'AI Second Brain';
    const publicPages = ['/login', '/register'];
    const token = localStorage.getItem('jwt_token');
    const isLoggedIn = token && token !== 'test_token_for_unlogin';

    if (!publicPages.includes(to.path) && !isLoggedIn) {
        next('/login');  // 未登录 → 重定向到登录页
    } else {
        next();
    }
});
```

### 4.3 状态管理 (Pinia Store)

#### 4.3.1 `user.js` — 用户状态

```javascript
state: { userInfo, token, isLogin }
persist: { key: 'user-store', storage: localStorage }  // 持久化到 localStorage
```

**Actions**：
- `login(userData)` — POST `/user/login`，成功后保存 token 到 localStorage
- `logout()` — POST `/user/logout`，清除本地状态和 token
- `register(userData)` — POST `/user/register`，注册成功自动登录
- `getUserInfoDetail()` — GET `/user/detail`，获取用户详情
- `updateUserInfo(userData)` — PUT `/user/update`，更新用户信息
- `updatePassword()` — POST `/user/reset-password`，修改密码

#### 4.3.2 `session.js` — 会话状态

```javascript
state: { sessions: [], currentSession: null, loading: false }
```

**Actions**：
- `getUserSessions(userId)` — 获取用户的所有会话，按时间降序排列
- `getSession(sessionId)` — 获取单个会话详情（含历史消息）
- `deleteSession(sessionId)` — 删除会话
- `createSession(query)` — 通过发送第一个消息创建会话，从 SSE 流中提取 sessionId

### 4.4 核心页面实现细节

#### 4.4.1 `AIChat.vue` — AI 对话页

**SSE 流式响应处理**：

```javascript
const fetchAIResponse = async (userMessage) => {
    const response = await fetch('/chat/agent/query/stream', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, query: userMessage })
    });

    const reader = response.body.getReader();
    const decoder = new TextDecoder();

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        // 解析 SSE 数据流
        // 处理 thinking / response / done / error 事件
    }
};
```

**事件处理逻辑**：
- `thinking` 事件：追加思考步骤到消息的 `thinking` 数组，支持展开/折叠
- `response` 事件：逐字符追加到 AI 消息内容，实现打字机效果（每字符 8ms 延迟）
- `done` 事件：保存 sessionId，跳转到带 sessionId 的路由
- 首条 response 到达后 1.5 秒自动折叠思考过程

**思考过程展示**：

```javascript
// 阶段配置
const stageConfig = {
    planning:    { label: '规划',   color: '#8B7E6F' },
    researching: { label: '研究',   color: '#B8926E' },
    tool_call:   { label: '工具',   color: '#D4914A' },
    writing:     { label: '合成',   color: '#7D9B7A' },
    review:      { label: '审查',   color: '#C4856A' },
    complete:    { label: '完成',   color: '#6B8E6B' }
};
```

**会话历史恢复**：路由参数 `sessionId` 变化时，自动加载会话历史，并从 localStorage 恢复思考过程（最近 5 条）。

**Markdown 渲染**：使用 `marked` 解析 + `DOMPurify` 消毒 + `highlight.js` 代码高亮。

#### 4.4.2 `NoteEditor.vue` — 笔记编辑器

**双栏布局**：
- 左侧：Markdown 编辑器主区域（ByteMD）
- 右侧：关联笔记侧边栏（可折叠，宽度 320px）

**核心功能**：
1. **Markdown 编辑**：基于 ByteMD，支持 GFM 语法、代码高亮、数学公式、Mermaid 图表
2. **快捷工具栏**：`QuickToolbar` 组件提供常用的 Markdown 格式按钮
3. **行内 AI 补全**：`InlineCompletion` 组件，监听光标位置，将上下文发送给 LLM 获取补全建议
4. **自动保存草稿**：内容变化后 2 秒防抖保存到 localStorage
5. **关联笔记推荐**：内容变化后 3 秒防抖刷新关联推荐（仅侧边栏展开时）
6. **侧边栏笔记详情**：点击关联笔记可在侧边栏内直接查看完整内容（支持 Markdown 渲染）

**光标跟踪实现**：

```javascript
function setupCursorTracking() {
    const cm = markdownEditorRef.value?.getEditorCm();
    cm.on('cursorActivity', updateCursor);  // 监听光标移动
    cm.getScrollerElement().addEventListener('scroll', updateCursor);  // 监听滚动
}
```

#### 4.4.3 `NoteList.vue` — 笔记列表页

**功能**：
- 卡片式展示笔记列表
- 分类筛选栏（全部/工作/学习/生活/项目）
- 搜索功能（向量搜索）
- 下拉刷新 + 无限滚动分页
- 笔记预览（去除 Markdown 标记，取前 100 字）

#### 4.4.4 `KnowledgeBase.vue` — 知识库管理页

**功能**：
- 文件上传区域（支持拖拽和点击选择）
- 支持格式：`.md`, `.txt`, `.pdf`, `.docx`, `.pptx`
- SSE 流式上传进度展示（每个文件独立显示状态）
- 文档列表管理（查看内容、查看切片、删除）
- 文档详情弹窗（完整文本 + 按页分组的图片）
- 切片列表弹窗（每个切片的独立内容 + 关联图片）
- 批量清空功能

**SSE 上传进度解析**：

```javascript
const parseEvent = (event) => {
    const eventData = JSON.parse(data);
    const { event_type, filename, message, progress } = eventData;
    // 更新对应文件的进度条和状态文字
};
```

#### 4.4.5 `DailyReview.vue` — 每日回顾页

**功能**：
- 展示今日待回顾的笔记列表（卡片式）
- 点击卡片弹出选择题（LLM 生成）
- 选择答案后显示正确/错误反馈
- 标记已回顾后更新进度

**选择题交互流程**：

```
加载页面 → GET /review/today → 获取待复习笔记列表
点击卡片 → GET /review/question/{noteId} → 获取 LLM 生成的选择题
选择答案 → 显示正确/错误
点击"标记已回顾" → POST /review/done/{noteId} → 更新复习记录
```

### 4.5 Vite 代理配置

前端开发服务器运行在 `localhost:3000`，后端运行在 `localhost:8000`，通过 Vite 代理解决跨域：

```javascript
proxy: {
    '/chat/agent/':  { target: 'http://127.0.0.1:8000', ws: true },
    '/chat/rag/':    { target: 'http://127.0.0.1:8000' },
    '/chat/session/':{ target: 'http://127.0.0.1:8000' },
    '/knowledge/':   { target: 'http://127.0.0.1:8000' },
    '/note/':        { target: 'http://127.0.0.1:8000' },
    '/review/':      { target: 'http://127.0.0.1:8000' },
    '/evaluation/':  { target: 'http://127.0.0.1:8000' },
    '/user':         { target: 'http://127.0.0.1:8000' },
    '/health':       { target: 'http://127.0.0.1:8000' },
}
```

**注意**：代理路径的尾部斜杠设计是为了避免匹配前端的页面路由（如 `/chat` 是页面路由，`/chat/` 是 API 代理）。

### 4.6 主题系统

通过 CSS 变量实现主题切换：

```css
:root {
    --color-bg: #f7f8fa;
    --color-card: #ffffff;
    --color-text: #333333;
    --color-primary: #D4914A;
    /* ... */
}
```

`theme.js` store 管理主题状态，切换时在 `<html>` 上添加/移除 class，CSS 变量自动响应。

### 4.7 国际化 (i18n)

支持中文 (`zh-CN`) 和英文 (`en-US`)，通过 `vue-i18n` 实现。组件中使用 `$t('key')` 或 `t('key')` 引用翻译文本。

---

## 五、前后端联合数据流

### 5.1 用户登录流程

```
前端 Login.vue                    后端
    │                              │
    │  POST /user/login            │
    │  {username, password}        │
    │ ─────────────────────────→   │
    │                              │  AuthService.login()
    │                              │  → 验证密码 (BCrypt)
    │                              │  → 生成 JWT Token
    │  {user, token}               │
    │ ←─────────────────────────   │
    │                              │
    │  localStorage.setItem('jwt_token', token)
    │  pinia store 更新状态
    │  router.push('/notes')
```

### 5.2 AI 对话流程

```
前端 AIChat.vue                   后端
    │                              │
    │  POST /chat/agent/query/stream
    │  Authorization: Bearer xxx
    │  {query, sessionId}          │
    │ ─────────────────────────→   │
    │                              │  Tomcat线程：创建 SseEmitter(300秒) → 提交到异步线程池 → 立即返回
    │                              │  async线程：
    │                              │  → 恢复 SecurityContext
    │                              │  → 先保存用户消息
    │                              │  → 加载会话历史 → ContextManager 三层压缩
    │                              │  → SupervisorService.plan() 判断复杂度
    │                              │
    │                              │  [简单查询] 单 Agent：
    │                              │  → processWithFunctionCalling() 循环（最多3轮）
    │                              │    → Function Calling → 工具执行 → 质量审查
    │                              │
    │                              │  [复杂查询] 多 Agent 流水线：
    │                              │  → Supervisor 拆分为 2-4 个子任务
    │                              │  → 并行执行各子任务（每个最多2轮工具调用）
    │                              │  → WriterService 合成最终回答
    │                              │  → QualityReviewer 质量审查
    │                              │
    │  SSE: thinking 事件           │
    │ ←─────────────────────────   │  (planning: "已拆分为 N 个子任务")
    │  SSE: thinking 事件           │
    │ ←─────────────────────────   │  (researching: "[R-1] xxx 执行中")
    │  SSE: thinking 事件           │
    │ ←─────────────────────────   │  (writing: "正在整合 N 个子任务结果")
    │  SSE: thinking 事件           │
    │ ←─────────────────────────   │  (complete: "已处理完成")
    │  SSE: response 事件 (多条)    │
    │ ←─────────────────────────   │  (分块流式返回，每50字符一条)
    │  SSE: done 事件               │
    │ ←─────────────────────────   │  (含 session_id, trace_id, token_used, token_max)
    │                              │
    │  逐字符打字机显示
    │  保存 sessionId 到路由
    │  展示 token 用量
```

### 5.3 笔记创建流程

```
前端 NoteEditor.vue               后端
    │                              │
    │  POST /note/create           │
    │  {title, content}            │
    │ ─────────────────────────→   │
    │                              │  NoteService.createNote()
    │                              │  → 保存 MySQL
    │                              │  → 添加向量索引
    │                              │  → 事务提交
    │  {id, title, ...}            │
    │ ←─────────────────────────   │
    │                              │  → @Async 异步执行：
    │                              │    → LLM 自动生成标签/分类
    │                              │    → 创建复习记录
    │  router.replace(/notes/:id)  │
```

### 5.4 知识库上传流程

```
前端 KnowledgeBase.vue            后端
    │                              │
    │  POST /knowledge/add/multiple/stream
    │  FormData: files[]           │
    │ ─────────────────────────→   │
    │                              │  KnowledgeService.uploadMultipleStream()
    │                              │  → 创建 SseEmitter
    │                              │  → 异步处理每个文件：
    │  SSE: {stage:"loading"}      │
    │ ←─────────────────────────   │    → Tika 解析
    │  SSE: {stage:"splitting"}    │
    │ ←─────────────────────────   │    → 分块
    │  SSE: {stage:"storing"}      │
    │ ←─────────────────────────   │    → 向量化存储
    │  SSE: {stage:"completed"}    │
    │ ←─────────────────────────   │
    │  SSE: {stage:"all_completed"}│
    │ ←─────────────────────────   │
    │                              │
    │  刷新文档列表
```

### 5.5 每日回顾流程

```
前端 DailyReview.vue              后端
    │                              │
    │  GET /review/today           │
    │ ─────────────────────────→   │
    │                              │  ReviewService.getTodayReviews()
    │                              │  → 查询 nextReviewAt <= now 的记录
    │  {reviews: [...]}            │
    │ ←─────────────────────────   │
    │                              │
    │  用户点击卡片                 │
    │  GET /review/question/:id    │
    │ ─────────────────────────→   │
    │                              │  ReviewService.generateQuestion()
    │                              │  → LLM 生成选择题
    │  {question, choices, answer} │
    │ ←─────────────────────────   │
    │                              │
    │  用户选择答案 → 显示结果      │
    │  POST /review/done/:id       │
    │ ─────────────────────────→   │
    │                              │  ReviewService.markReviewed()
    │                              │  → 更新复习次数和下次复习时间
    │  {success, nextReviewAt}     │
    │ ←─────────────────────────   │
```

---

## 六、数据库设计

### 6.1 表结构

```sql
-- 用户表
CREATE TABLE user_service (
    uuid        VARCHAR(32) PRIMARY KEY,
    username    VARCHAR(150),
    email       VARCHAR(255) UNIQUE NOT NULL,
    telephone   VARCHAR(11) UNIQUE,
    password    VARCHAR(255) NOT NULL,
    status      INT DEFAULT 1,          -- 0=禁用, 1=正常, 2=锁定
    gender      INT,                    -- 1=男, 2=女, 3=其他
    bio         TEXT,
    avatar      VARCHAR(255),
    date_joined DATETIME,
    last_login  DATETIME
);

-- 笔记表
CREATE TABLE notes (
    id          VARCHAR(36) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    content     TEXT NOT NULL,
    tags        JSON,                   -- ["tag1", "tag2"]
    category    VARCHAR(50),            -- work/study/life/project
    created_at  DATETIME,
    updated_at  DATETIME
);

-- 聊天会话表
CREATE TABLE chat_sessions (
    session_id  VARCHAR(36) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL,
    title       VARCHAR(200),
    created_at  DATETIME,
    updated_at  DATETIME
);

-- 聊天消息表
CREATE TABLE chat_messages (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(36) NOT NULL,   -- 外键关联 chat_sessions
    role        VARCHAR(32) NOT NULL,   -- human / ai
    content     TEXT NOT NULL,
    metadata    JSON,
    created_at  DATETIME
);

-- 复习记录表
CREATE TABLE review_records (
    id              VARCHAR(36) PRIMARY KEY,
    note_id         VARCHAR(36) NOT NULL,
    user_id         VARCHAR(36) NOT NULL,
    review_count    INT DEFAULT 0,
    interval_days   INT DEFAULT 1,
    next_review_at  DATETIME,
    last_reviewed_at DATETIME
);

-- RAG 追踪表（评估模块）
CREATE TABLE rag_traces (
    trace_id            VARCHAR(36) PRIMARY KEY,
    user_id             VARCHAR(36),
    query               TEXT,
    retrieved_docs      JSON,           -- 检索文档预览列表
    retrieved_doc_count INT,
    avg_similarity      DOUBLE,
    retrieval_latency_ms BIGINT,
    generation_latency_ms BIGINT,
    total_latency_ms    BIGINT,
    final_answer        TEXT,
    user_feedback       INT,            -- 用户评分 1-5
    feedback_reason     VARCHAR(500),
    created_at          DATETIME
);

-- 评估报告表
CREATE TABLE evaluation_reports (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id            VARCHAR(36),
    user_id             VARCHAR(36),
    query               TEXT,
    context_precision   DOUBLE,         -- 上下文精度 (0-1)
    context_recall      DOUBLE,         -- 上下文召回 (0-1)
    faithfulness        DOUBLE,         -- 忠实度 (0-1)
    answer_relevancy    DOUBLE,         -- 答案相关性 (0-1)
    rule_score          INT,            -- 规则评分 (0-100)
    total_score         INT,            -- 综合评分 (0-100)
    level               VARCHAR(20),    -- 评级：优秀/良好/及格/不及格
    diagnosis           TEXT,           -- 自动诊断
    created_at          DATETIME
);
```

---

## 七、开发中的关键设计决策

### 7.1 多 Agent 流水线架构

系统采用 Supervisor → Worker → Writer 的多 Agent 架构：

- **Supervisor**（`SupervisorService`）：用精确模型分析查询复杂度，决定走单 Agent 还是多 Agent 流水线
- **Worker**（`executeSubTask()`）：每个子任务独立 Agent 循环，最多 2 轮工具调用，轻量级
- **Writer**（`WriterService`）：合并去重多个子任务结果，生成结构化最终回答
- **质量审查**（`QualityReviewer`）：回答不达标时带反馈重试一次

**为什么不用单 Agent**：复杂查询（如"对比 A 和 B 的优缺点，再给个建议"）需要多轮工具调用，单 Agent 容易遗漏或混淆。拆分为并行子任务后，每个子任务专注一个维度，结果更完整。

### 7.2 Agent 回答质量审查

参考 sage-research 的 Supervisor Review 机制，在 Agent 回答阶段进行质量审查：

```
Agent 回答 → QualityReviewer.reviewAnswer() → 不达标 → 带反馈重新生成
```

**注意**：质量审查在 AgentService 层面执行（`reviewAndRetryIfNeeded()`），而非 RagService。RagService 本身是简单的"检索 → 生成"流程。

**为什么需要**：LLM 生成的回答可能存在幻觉（编造信息）或答非所问，质量审查能自动发现并纠正。

### 7.3 Function Calling 的选型与实现

当前 Agent 使用 LangChain4j 原生 Function Calling 机制：通过 `@Tool` 注解声明工具，`ToolSpecifications.toolSpecificationsFrom()` 自动提取工具定义发送给 LLM，LLM 返回结构化的 `ToolExecutionRequest` 而非自由文本。

相比早期的关键词文本匹配方案，Function Calling 的优势：
- LLM 模型层面决定工具调用，不会误触发
- 支持 LLM 自主填写工具参数（JSON 格式）
- 支持单轮返回多个工具调用请求（并行执行）
- 工具结果通过 `ToolExecutionResultMessage` 结构化回传

`@Tool` 注解示例（`AgentTools.java`）：

```java
@Tool("基于RAG检索知识库并生成摘要")
public String ragSummary(String query, String userId) { ... }

@Tool("搜索用户的笔记")
public String searchNotes(String query, String userId) { ... }
```

### 7.4 为什么 SSE 而不是 WebSocket

- SSE 是单向通信（服务端→客户端），适合流式输出场景
- 实现简单，基于 HTTP，不需要额外的协议升级
- 自动重连机制
- 前端用 `fetch` + `ReadableStream` 即可处理

### 7.5 为什么笔记用 UUID 而不是自增 ID

- 分布式友好：多实例部署时不会冲突
- 安全性：不会暴露系统中的笔记数量
- 前端可预生成 ID

### 7.6 向量存储架构：MySQL + ChromaDB + BM25

当前 `VectorStoreService` 采用三路存储架构：

- **MySQL**：存储文档元数据和切片内容，支持事务和复杂查询
- **ChromaDB**：存储向量，支持高效的相似度检索（通过 LangChain4j `ChromaEmbeddingStore` 接入）
- **BM25**：Lucene 内存索引，支持关键词检索，与向量检索形成互补

**降级策略**：ChromaDB 不可用时，知识库检索降级为 MySQL + 内存余弦相似度；笔记检索不可用。ChromaDB 恢复后可重试向量化（`retryVectorization()`）。

**为什么用三路而不是纯 ChromaDB**：MySQL 提供数据持久化和事务保证，BM25 提供关键词精确匹配（向量检索对精确关键词不敏感），ChromaDB 提供语义相似度检索。三者互补，通过 RRF 融合排序。

### 7.7 SecurityContext 为什么需要特殊处理

Spring Security 默认使用 `ThreadLocal` 存储认证信息。但在 SSE（异步线程）场景下，请求线程和执行线程不同，导致认证信息丢失。解决方案：
1. `SecurityConfig` 中设置 `MODE_INHERITABLETHREADLOCAL`，让子线程继承父线程的 SecurityContext
2. `AgentService` 中手动捕获并设置 SecurityContext

---

## 八、项目当前状态与待完善点

### 8.1 已完成

- [x] 用户注册/登录/JWT 认证
- [x] 笔记 CRUD + 分类 + 标签
- [x] LLM 自动标签分类
- [x] AI Agent 对话（SSE 流式 + 多 Agent 流水线）
- [x] RAG 检索 + 多 Query 扩展 + 向量 + BM25 + RRF 融合
- [x] Cross-Encoder 精排（智谱 AI rerank API + 动态 top-N）
- [x] Agent 回答质量审查（QualityReviewer 审查不达标时带反馈重试）
- [x] 多 Agent 流水线（Supervisor 规划 + 并行子任务 + Writer 合成 + Clarifier 澄清）
- [x] 上下文三层压缩（Token 计数 + 工具结果压缩 + 摘要压缩）
- [x] RAG 评估系统（四指标 LLM 评估 + 规则评估 + 定时批量评估 + 周报）
- [x] 知识库文档上传 + 解析 + 分块
- [x] 间隔复习系统
- [x] 前端完整 UI（笔记/对话/知识库/复习）
- [x] 国际化（中/英）
- [x] 主题切换

### 8.2 待完善

- [x] 向量存储已接入 ChromaDB（MySQL + ChromaDB + BM25 三路存储）
- [x] 重排序服务接入 Cross-Encoder 模型（已使用智谱 AI rerank API + 动态 top-N 策略）
- [x] Agent 工具调用改为 Function Calling API（已使用 LangChain4j 原生 @Tool 注解 + toolSpecifications）
- [x] 多 Agent 流水线（Supervisor 规划 + 并行子任务 + Writer 合成）
- [x] Agent 回答质量审查（QualityReviewer 审查不达标时带反馈重试）
- [x] 多 Query 扩展（QueryExpander）
- [x] 混合检索（向量 + BM25 + RRF 融合 + Cross-Encoder 精排）
- [x] RAG 评估系统（四指标 LLM 评估 + 规则评估 + 定时任务）
- [x] 上下文三层压缩（Token 计数 + 工具结果压缩 + 摘要压缩）
- [x] Token 用量统计和前端展示
- [ ] 笔记编辑器行内 AI 补全优化
- [ ] 复习选择题由 LLM 动态生成（当前为占位）
- [ ] 文件上传支持更多格式
- [ ] 笔记导出功能完善
- [ ] 移动端适配优化

---

## 九、RAGAS 评估框架分析与集成方案

### 9.1 RAGAS 是什么

RAGAS（Retrieval Augmented Generation Assessment）是一个开源的 RAG 评估框架，核心思想是**用 LLM 当裁判**，自动评估 RAG 系统的检索质量和回答质量。

**四大核心指标**：

| 指标 | 评估什么 | 计算方式 | 输入 |
|------|---------|---------|------|
| **Context Precision** | 检索的文档是否与问题相关 | LLM 判断每个检索文档是否相关，按排名加权 | Question + Contexts |
| **Context Recall** | 检索是否找全了所需信息 | LLM 从参考答案中提取声明，检查每个声明是否被检索文档覆盖 | Question + Answer + Ground Truth |
| **Faithfulness** | 回答是否基于检索文档（有无幻觉） | LLM 将回答拆分为多个声明，逐个检查是否能从检索文档中推导 | Question + Answer + Contexts |
| **Answer Relevancy** | 回答是否回应了用户问题 | LLM 基于回答反向生成 N 个问题，计算与原始问题的相似度 | Question + Answer |

**RAGAS 的工作流程**：

```
输入：Question + Answer（AI生成） + Contexts（检索到的文档） + Ground Truth（标准答案，可选）
  │
  ├─ Context Precision → LLM 逐文档判断相关性 → 加权得分
  ├─ Context Recall → LLM 从标准答案提取声明 → 检查覆盖率
  ├─ Faithfulness → LLM 将回答拆分为声明 → 逐个验证
  └─ Answer Relevancy → LLM 反向生成问题 → 计算相似度
  │
  输出：四个指标分数（0-1）+ 综合评分
```

### 9.2 NLI 模型详解

**什么是 NLI 模型？**

NLI（Natural Language Inference，自然语言推理）模型是专门判断两个句子之间逻辑关系的模型。

**输入**：两个句子
- 前提（Premise）：已知的事实/上下文
- 假设（Hypothesis）：需要验证的声明

**输出**：三分类
- **Entailment（蕴含）**：假设可以从前提推导出来
- **Contradiction（矛盾）**：假设与前提冲突
- **Neutral（无关）**：假设与前提无关，无法判断

**示例**：
```
前提："Python 是一种解释型语言，由 Guido van Rossum 于 1991 年创建"

假设1："Python 由 Guido 创建"
→ Entailment（可以从前提推导）

假设2："Python 是编译型语言"
→ Contradiction（与前提矛盾）

假设3："Python 是最好的编程语言"
→ Neutral（前提中无法判断"最好"）
```

**RAGAS 用 NLI 模型做什么？**

在 Faithfulness 评估中，RAGAS 用 NLI 模型替代 LLM 来判断每个声明是否能从检索文档推导：
```
前提 = 检索到的文档内容
假设 = 回答中的某个声明
→ NLI 模型判断：Entailment = 忠实，Contradiction = 幻觉，Neutral = 不确定
→ 忠实度 = Entailment 数量 / 总声明数量
```

**RAGAS 使用的典型 NLI 模型**：
- `microsoft/deberta-v3-large-mnli`（DeBERTa v3 Large，基于 MNLI 数据集微调）
- `cross-encoder/nli-deberta-v3-base`（Cross-Encoder 版本，更快）
- 参数量约 300M-900M，需要 GPU 加速（或用 CPU 但较慢）

### 9.3 测试数据集：自动生成 vs 手动创建

**Python RAGAS 的 TestsetGenerator（自动生成）**：

RAGAS 内置 `TestsetGenerator`，可以基于文档自动生成测试数据集：

```
输入：用户的文档列表（知识库中的文档）
  │
  ├─ Step 1: 用 LLM 从文档中提取关键知识点
  ├─ Step 2: 用 LLM 基于知识点生成不同难度的问题：
  │   ├─ Simple Question：直接从文档中找答案
  │   ├─ Multi-hop Question：需要综合多个文档段落
  │   └─ Reasoning Question：需要推理才能回答
  ├─ Step 3: 用 LLM 生成对应的 Ground Truth（标准答案）
  │
  输出：{question, ground_truth, contexts} 列表
```

**优势**：不需要人工标注，可以从已有文档快速生成大量测试数据。
**劣势**：生成的质量依赖 LLM，可能生成不合理的问题或不准确的标准答案。

**Java 实现方式**：

同样可以用 LLM 自动生成，不需要手动创建：

```java
// 伪代码：Java 实现合成测试数据生成
public List<TestCase> generateTestCases(List<Document> documents) {
    for (Document doc : documents) {
        // Step 1: 用 LLM 提取知识点
        String keyPoints = llm.extractKeyPoints(doc.content);
        // Step 2: 用 LLM 生成问题
        String question = llm.generateQuestion(keyPoints);
        // Step 3: 用 LLM 生成标准答案
        String groundTruth = llm.generateAnswer(question, doc.content);
        testCases.add(new TestCase(question, groundTruth, doc));
    }
    return testCases;
}
```

**Java 也可以自动生成测试数据集**，只是需要自己写生成逻辑（3 个 LLM 调用），而 Python RAGAS 内置了这个功能。

### 9.4 LLM 评估 vs NLI 模型评估

**两种 Faithfulness 评估方式对比**：

| 维度 | LLM-as-a-judge | NLI 模型 |
|------|---------------|---------|
| **准确率** | 85-90%（GPT-4 级别） | 90-95%（DeBERTa-v3-large-mnli） |
| **速度** | 慢（每次评估需调用 LLM API，约 1-3 秒） | 快（本地推理，约 0.1-0.5 秒/声明） |
| **成本** | 高（每次评估消耗 Token） | 低（一次性加载模型，无 API 费用） |
| **依赖** | 无额外依赖 | 需要 PyTorch/ONNX Runtime + 模型文件（约 1-2GB） |
| **灵活性** | 高（Prompt 可自定义评估标准） | 低（固定三分类，无法理解复杂语义） |
| **中文支持** | 好（LLM 原生支持中文） | 差（主流 NLI 模型是英文，中文需额外微调） |

**关键结论**：
- NLI 模型准确率高 5-10%，但**中文场景下优势消失**（主流 NLI 模型是英文的）
- LLM-as-a-judge 更灵活，可以通过 Prompt 调整评估标准
- 本项目是中文场景，**LLM-as-a-judge 是更实际的选择**

**Java 如何用 NLI 模型？**

如果确实需要 NLI 模型，Java 有两条路径：

```
路径1: ONNX Runtime（推荐）
  将 HuggingFace 的 NLI 模型转换为 ONNX 格式
  → Java 通过 onnxruntime-java 加载模型
  → 输入两个句子，输出三分类概率
  优点：纯 Java，无 Python 依赖
  缺点：需要手动转换模型，中文模型需自行寻找

路径2: Python 微服务
  Python 服务加载 NLI 模型，暴露 HTTP API
  → Java 后端调用
  优点：直接用 HuggingFace 生态
  缺点：需要额外 Python 服务
```

### 9.5 LLM 评估与 RAG 检索用同一个模型：自己检测自己？

**问题**：如果 RAG 生成用 GLM-4，评估也用 GLM-4，这不是"自己检测自己"吗？

**答案**：是的，但这在 RAGAS 中是标准做法，而且实际效果不错。

**为什么"自己检测自己"可行？**

```
生成任务 vs 判断任务，难度完全不同：

生成任务（RAG 回答）：
  → 需要创造性，从无到有生成内容
  → 容易编造信息（幻觉）
  → 模型的"想象力"会导致错误

判断任务（评估忠实度）：
  → 是二分类/三分类任务
  → 给定前提和假设，判断逻辑关系
  → 模型的"理解力"足够完成
  → 类似于：学生可能写错作文，但能判断别人的作文对不对
```

**但确实存在偏差风险**：

1. **自我偏好偏差**：模型可能倾向于"放过"自己生成的错误
2. **系统性盲区**：如果模型对某类知识有系统性误解，评估时也检测不出来
3. **过度自信**：模型可能高估自己回答的质量

**缓解方案**：

```
方案1: 用更强的模型评估（推荐）
  RAG 生成用 GLM-4（成本低、速度快）
  评估用 GPT-4 或 Claude（更准确、偏差更小）
  优点：评估更客观
  缺点：评估成本更高

方案2: 用不同厂商的模型
  RAG 生成用智谱 GLM-4
  评估用通义千问或 Ollama 本地模型
  优点：避免同一模型的系统性偏差
  缺点：需要配置多个 LLM

方案3: LLM + 规则混合评估（当前项目已实现）
  LLM 评估四个指标（70% 权重）
  + 规则评估耗时、Token、相似度等硬指标（30% 权重）
  规则评估不依赖 LLM，可以作为客观基线

方案4: 引入用户反馈作为校准
  用户对回答评分 1-5 分
  → 对比 LLM 评估分数与用户评分的相关性
  → 如果相关性低，说明 LLM 评估有偏差，需要调整 Prompt
```

**本项目的推荐方案**：

```
RAG 生成：GLM-4（现有配置）
LLM 评估：GLM-4（现有配置，成本最低）
规则评估：硬指标（已有，不依赖 LLM）
用户反馈：人工校准（已有，可检测 LLM 评估偏差）
```

### 9.6 集成方案：纯 Java 增强 vs Python RAGAS

#### 方案 A：纯 Java 增强（推荐）

**不引入 Python/RAGAS**，在现有 `EvaluationService` 基础上补齐差距。

**需要增强的四个点**：

```
1. Faithfulness 增强：声明拆分验证
   当前：整体判断回答是否忠实
   增强：先用 LLM 将回答拆分为 N 个独立声明
         → 逐个声明检查是否能从检索文档中推导
         → 忠实度 = 可推导的声明数 / 总声明数
   Prompt：
     "请将以下回答拆分为独立的事实性声明（每个声明一行）：\n回答：{answer}\n声明列表："
     "以下声明是否能从参考资料中推导出来？回答"是"或"否"：\n参考资料：{context}\n声明：{claim}"

2. Answer Relevancy 增强：反向生成 + 相似度
   当前：直接判断回答是否相关
   增强：用 LLM 基于回答生成 3 个假设性问题
         → 计算假设问题与原始问题的 Embedding 余弦相似度
         → 相关性 = 平均相似度
   Prompt：
     "基于以下回答，生成 3 个可能被提出的问题（每个问题一行）：\n回答：{answer}\n问题列表："

3. Context Recall 增强：引入标准答案
   当前：只用 Question+Answer+Contexts
   增强：在 RagTrace 中增加 groundTruth 字段
         → 用 LLM 从标准答案中提取关键声明
         → 检查每个声明是否被检索文档覆盖

4. 测试数据集自动生成
   用 LLM 从已有文档自动生成测试数据：
   → 从文档提取知识点 → 生成问题 → 生成标准答案
   → 存入 evaluation_test_cases 表
   → 建立基线，后续优化 RAG 时对比效果
```

#### 方案 B：Python RAGAS 库集成

**RAGAS 的独特能力**：
- **TestsetGenerator**：从文档自动生成不同难度的 Question + Ground Truth（Java 需自己实现，3 个 LLM 调用）
- **NLI 模型**：DeBERTa-v3-large-mnli，准确率 90-95%，但中文支持差
- **可视化报告**：内置评估结果可视化（Java 需自建前端）

**集成方式**：
```
Java 后端 → HTTP 请求 → Python Flask/FastAPI 服务 → RAGAS 库
  │
  │  POST /evaluate → {faithfulness, answer_relevancy, context_precision, context_recall}
  │  POST /generate-testset → {test_cases: [{question, ground_truth, contexts}, ...]}
```

### 9.7 综合对比与推荐

| 维度 | Java 增强方案 | Python RAGAS |
|------|-------------|-------------|
| **额外依赖** | 无 | Python + ragas + torch + transformers（~2-3GB） |
| **部署复杂度** | 低（现有 Spring Boot 内） | 高（需额外 Python 服务） |
| **Faithfulness 准确率** | 85-90%（LLM-as-a-judge） | 90-95%（NLI 模型，但中文场景差距缩小） |
| **Faithfulness 中文支持** | 好（LLM 原生中文） | 差（主流 NLI 模型是英文） |
| **测试数据自动生成** | 需自己实现（3 个 LLM 调用） | 内置 TestsetGenerator |
| **评估成本** | 每次评估消耗 LLM Token | NLI 模型无 API 费用，但 LLM 部分仍需 Token |
| **可视化报告** | 需自建前端 | 内置 |
| **运维成本** | 低 | 中（Python 环境维护） |
| **自定义灵活性** | 高（完全可控） | 中（受限于库 API） |

**推荐**：方案 A（纯 Java 增强）。理由：
1. 中文场景下 LLM-as-a-judge 与 NLI 模型差距不大
2. 当前项目已实现 80% 的 RAGAS 能力，补齐成本低
3. 可以保留规则评估和自动诊断等项目特有功能
4. 零额外运维成本
5. 测试数据集也可以用 LLM 自动生成，不需要手动创建
# 十、利用 RAGAS 设计消融实验
对比向量 + BM25+RRF+Cross-Encoder」检索链路优化效果




