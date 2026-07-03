# RAG-Note 项目架构与开发思路梳理

> AI 驱动的智能知识管理笔记系统，基于 RAG（检索增强生成）+ 间隔复习 + Agent 智能体

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
│  └──────────────────────┬───────────────────────────┘           │
│  ┌──────────────────────┴───────────────────────────┐           │
│  │               Service Layer (业务逻辑)            │           │
│  │  AgentService │ RagService │ NoteService          │           │
│  │  ChatService │ KnowledgeService │ ReviewService   │           │
│  └──────┬────────────┬────────────┬─────────────────┘           │
│  ┌──────┴──────┐ ┌───┴────┐ ┌────┴──────────────┐              │
│  │ LangChain4j │ │  JPA   │ │  Redis Cache      │              │
│  │ (LLM调用)   │ │ (MySQL)│ │  (Token黑名单等)  │              │
│  └─────────────┘ └────────┘ └───────────────────┘              │
│  ┌─────────────────────────────────────────────────┐           │
│  │  VectorStoreService (内存/ChromaDB 向量存储)     │           │
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
| 向量存储     | 内存模拟 (设计对接 ChromaDB)      | -       | 向量相似度检索                  |
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

这是整个系统的"大脑"，负责理解用户意图并调度各种工具完成任务。

#### 3.2.1 `ModelFactory.java` — 模型工厂

**设计模式**：工厂模式，统一封装三种 LLM 的创建逻辑。

```
createChatModel()       → 根据 app.llm.type 配置创建对话模型
createEmbeddingModel()  → 根据 app.embed.type 配置创建嵌入模型
createVisionModel()     → 根据 app.vision.type 配置创建视觉模型
```

**实现细节**：
- 智谱 GLM：通过 `OpenAiChatModel` 适配（智谱提供 OpenAI 兼容 API），设置 `baseUrl` 指向 `open.bigmodel.cn`
- Ollama 本地模型：使用 `OllamaChatModel`，连接本地 `localhost:11434`
- 通义千问：使用 `QwenChatModel`（LangChain4j 原生支持阿里 DashScope）
- 所有模型统一设置 `temperature=0.7`，平衡创造性与稳定性

#### 3.2.2 `AgentService.java` — Agent 核心服务

**核心职责**：接收用户消息，通过 LLM 推理决定是否调用工具，最终以 SSE 流式返回结果。

**`streamAgentResponse()` 方法流程**：

```
1. 创建 SseEmitter（120秒超时）
2. 捕获当前线程的 SecurityContext（解决异步线程认证丢失问题）
3. 提交到异步线程池执行：
   a. 恢复 SecurityContext
   b. 加载会话历史消息
   c. 构建消息列表：[SystemPrompt, 历史消息..., 用户新消息]
   d. 调用 processWithFunctionCalling() 执行 Agent 工具调用循环
   e. 发送 SSE thinking 事件（处理完成状态）
   f. 以 50 字符为单位分块流式发送响应（模拟打字机效果）
   g. 保存用户消息和 AI 回复到数据库
   h. 发送 SSE done 事件
   i. finally 清除 SecurityContext
4. 立即返回 emitter（释放 Tomcat 线程）
```

**Agent 工具调用循环 (`processWithFunctionCalling()`)**：

使用 LangChain4j 原生 Function Calling 机制，工具定义通过 `@Tool` 注解自动提取：

```java
// 构造函数中：从 AgentTools 的 @Tool 注解自动提取工具定义
this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);
```

```
Agent 循环（最多 3 轮）：
  1. 将消息列表 + 工具定义一起发给 LLM
  2. LLM 返回 AiMessage：
     ├─ hasToolExecutionRequests() == true → LLM 要求调用工具
     │   a. 发送 SSE thinking 事件（"正在调用工具: xxx"）
     │   b. 将 AI 的工具调用请求加入消息历史
     │   c. 逐个执行工具，解析 JSON 参数并注入 userId
     │   d. 将工具执行结果（ToolExecutionResultMessage）加入消息历史
     │   e. 继续下一轮循环 → LLM 基于工具结果决定下一步
     │
     └─ hasToolExecutionRequests() == false → LLM 直接返回文本
         → 结束循环，返回回复内容
```

**循环的用途**：给 LLM 多次"思考 → 行动"的机会。工具执行本身不是最终答案，只是中间数据。LLM 需要看到工具结果后才能生成自然语言回答，或者基于第一个工具的结果决定是否调用第二个工具。典型场景：
- 1 轮：LLM 调用 RAG 检索 → 第 2 轮基于结果生成摘要
- 2 轮：LLM 先查笔记统计 → 再标记复习 → 第 3 轮综合回答
- 0 轮：用户闲聊 → LLM 直接回复，不调用工具

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

#### 3.2.3 `AgentTools.java` — 工具集

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
步骤1: HyDE 生成假设文档
  用户查询 → LLM 生成一个"假设性回答" → 用这个回答做向量检索
  （为什么？假设文档的语义更接近真实文档，比原始查询的检索效果更好）

步骤2: 双路检索
  ├─ 知识库检索：向量相似度搜索，返回 top-K 个文档块
  └─ 笔记检索：搜索用户笔记，返回 top-3 条
  合并结果：笔记优先，知识库其次

步骤3: Map 阶段 — 并发摘要生成
  最多取前 3 篇文档，每篇文档启动一个 CompletableFuture：
  ┌─ 文档1 → LLM 生成摘要（30秒超时）
  ├─ 文档2 → LLM 生成摘要（30秒超时）
  └─ 文档3 → LLM 生成摘要（30秒超时）
  并发执行，用 taskExecutor 线程池

步骤4: Reduce 阶段 — 摘要融合
  如果有多篇摘要 → LLM 二次融合为综合回答
  如果只有一篇 → 直接作为最终结果

步骤5: 返回 {documents: [...], summary: "..."}
```

**HyDE 技术详解**：

```java
private static final String HYDE_PROMPT = "基于以下问题，生成一个详细的假设性回答，"
    + "我会根据你的这个假设性回答在向量数据库里检索文档：\n\n问题：%s\n\n假设性回答：";
```

HyDE（Hypothetical Document Embeddings）的核心思想：用户问"什么是 RAG？"，LLM 先生成一段假设性的回答"RAG 是一种结合检索和生成的技术..."，然后用这段回答去向量库检索，语义匹配度远高于直接用问题检索。

#### 3.3.2 `VectorStoreService.java` — 向量存储服务

**当前实现**：使用 `ConcurrentHashMap` 在内存中模拟向量存储，分为两个独立的存储空间：

```java
Map<String, Map<String, Object>> noteVectors;      // 笔记向量
Map<String, Map<String, Object>> knowledgeVectors;  // 知识库文档向量
```

**设计上应对接 ChromaDB**（配置中已有 `app.chroma.url`），当前的搜索是简单的关键词匹配占位。

**关键操作**：
- `addNoteVector(note)` — 笔记创建/更新时，将标题+内容存入向量存储
- `searchKnowledge(userId, query, topK)` — 按用户隔离，搜索知识库文档
- `searchNotes(userId, query, topK)` — 按用户隔离，搜索笔记
- `getUserDocuments(userId)` — 获取用户的所有文档列表（按文件名分组）
- `getDocumentDetail(userId, filename)` — 获取文档详情（所有切片合并）

#### 3.3.3 `DocumentProcessor.java` — 文档处理器

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

**MD5 去重**：同一个文件不会重复处理，通过文件内容的 MD5 哈希判断。

#### 3.3.4 `ReorderService.java` — 重排序服务

**当前实现**：简单的关键词重叠率计算，作为 Cross-Encoder 的占位。

```java
// 计算查询词在文档中的命中率作为相关性分数
float score = (float) matchCount / Math.max(1, queryTerms.size());
```

**设计意图**：在生产环境中，应使用 Cross-Encoder 模型（如 ONNX Runtime 加载 bge-reranker）对检索结果进行精排，显著提升排序质量。

---

### 3.4 聊天模块 (`chat/`)

#### 3.4.1 `ChatController.java` — 聊天 API

| 端点                                     | 方法   | 功能                     |
|-----------------------------------------|--------|--------------------------|
| `POST /chat/agent/query/stream`         | POST   | Agent 流式查询（SSE）     |
| `POST /chat/rag/query`                  | POST   | RAG 直接查询（非流式）    |
| `GET /chat/session/{sessionId}`         | GET    | 获取会话历史              |
| `DELETE /chat/session/{sessionId}`      | DELETE | 删除会话                  |
| `GET /chat/sessions`                    | GET    | 获取所有会话列表          |
| `GET /chat/sessions/{userId}`           | GET    | 获取指定用户的会话        |
| `POST /chat/reorder`                    | POST   | 文档重排序                |

**SSE 流式响应设计**：`/chat/agent/query/stream` 返回 `SseEmitter`，前端通过 `EventSource` 或 `fetch` + `ReadableStream` 读取。事件类型：

```json
{"type": "thinking", "stage": "complete", "content": "已处理完成"}
{"type": "response", "content": "回答片段...", "session_id": "xxx"}
{"type": "done", "session_id": "xxx"}
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

### 3.8 用户认证模块 (`auth/` + `user/`)

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

### 3.9 配置模块 (`config/`)

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
    retrieval:  { label: '检索',   color: '#B8926E' },
    hyde:       { label: 'HyDE',   color: '#8B7E6F' },
    reorder:    { label: '重排序', color: '#D4914A' },
    summarize:  { label: '总结',   color: '#7D9B7A' }
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
    │                              │  Tomcat线程：创建 SseEmitter → 提交到异步线程池 → 立即返回
    │                              │  async线程：
    │                              │  → 恢复 SecurityContext
    │                              │  → 加载会话历史
    │                              │  → processWithFunctionCalling() Agent循环：
    │                              │    第1轮: 消息+工具定义 → LLM → 要求调工具
    │                              │    → 执行工具 → 结果加入消息 → 继续循环
    │                              │    第2轮: LLM 看到工具结果 → 生成最终回答
    │                              │
    │  SSE: thinking 事件           │
    │ ←─────────────────────────   │  (tool_call: "正在调用工具: ragSummary")
    │  SSE: thinking 事件           │
    │ ←─────────────────────────   │  (complete: "已处理完成")
    │  SSE: response 事件 (多条)    │
    │ ←─────────────────────────   │  (分块流式返回，每50字符一条)
    │  SSE: done 事件               │
    │ ←─────────────────────────   │
    │                              │
    │  逐字符打字机显示
    │  保存 sessionId 到路由
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
```

---

## 七、开发中的关键设计决策

### 7.1 Function Calling 的选型与实现

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

### 7.2 为什么 SSE 而不是 WebSocket

- SSE 是单向通信（服务端→客户端），适合流式输出场景
- 实现简单，基于 HTTP，不需要额外的协议升级
- 自动重连机制
- 前端用 `fetch` + `ReadableStream` 即可处理

### 7.3 为什么笔记用 UUID 而不是自增 ID

- 分布式友好：多实例部署时不会冲突
- 安全性：不会暴露系统中的笔记数量
- 前端可预生成 ID

### 7.4 向量存储为什么用内存模拟

当前 `VectorStoreService` 使用 `ConcurrentHashMap` 而不是真正的向量数据库，原因是：
- 开发阶段快速验证功能
- 避免引入外部依赖（ChromaDB 需要单独部署）
- 设计上已预留 ChromaDB 接口（配置中有 `app.chroma.url`），后续可无缝切换

### 7.5 SecurityContext 为什么需要特殊处理

Spring Security 默认使用 `ThreadLocal` 存储认证信息。但在 SSE（异步线程）场景下，请求线程和执行线程不同，导致认证信息丢失。解决方案：
1. `SecurityConfig` 中设置 `MODE_INHERITABLETHREADLOCAL`，让子线程继承父线程的 SecurityContext
2. `AgentService` 中手动捕获并设置 SecurityContext

---

## 八、项目当前状态与待完善点

### 8.1 已完成

- [x] 用户注册/登录/JWT 认证
- [x] 笔记 CRUD + 分类 + 标签
- [x] LLM 自动标签分类
- [x] AI Agent 对话（SSE 流式）
- [x] RAG 检索 + HyDE + Map-Reduce 摘要
- [x] 知识库文档上传 + 解析 + 分块
- [x] 间隔复习系统
- [x] 前端完整 UI（笔记/对话/知识库/复习）
- [x] 国际化（中/英）
- [x] 主题切换

### 8.2 待完善

- [ ] 向量存储切换到 ChromaDB（当前内存模拟）
- [ ] 重排序服务接入 Cross-Encoder 模型
- [x] Agent 工具调用改为 Function Calling API（已使用 LangChain4j 原生 @Tool 注解 + toolSpecifications）
- [ ] 笔记编辑器行内 AI 补全优化
- [ ] 复习选择题由 LLM 动态生成（当前为占位）
- [ ] 文件上传支持更多格式
- [ ] 笔记导出功能完善
- [ ] 移动端适配优化
