# RAG 知识库数据同步与一致性 — 技术文档

> 对应简历描述：*用 Tika 解析与重叠切片处理文档，结合 MD5 去重与状态机，保障 MySQL、BM25、ChromaDB 数据一致。*

---

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [Tika 文档解析](#2-tika-文档解析)
3. [重叠切片处理](#3-重叠切片处理)
4. [MD5 去重](#4-md5-去重)
5. [状态机](#5-状态机)
6. [MySQL + BM25 + ChromaDB 三库一致性](#6-mysql--bm25--chromadb-三库一致性)
7. [异常场景与容错机制](#7-异常场景与容错机制)
8. [简历学习重点指南](#8-简历学习重点指南)

---

## 1. 整体架构概览

### 1.1 数据流全景

```
用户上传文件
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  DocumentProcessor.processFile()                     │
│                                                      │
│  ① MD5 去重检查（内存 + MySQL 双重验证）               │
│  ② Tika/PDFBox 文本提取                              │
│  ③ RagChunker 重叠切片（结构感知，扁平化）               │
│  ④ 切片质量校验                                      │
│  ⑤ MySQL 写入（同步，事务内）                         │
│  ⑥ BM25 写入（同步，同一事务内）                       │
│  ⑦ MD5 记录持久化                                    │
│  ⑧ ChromaDB 异步写入（documentExecutor 线程池）       │
│      └─ 成功 → status=completed                      │
│      └─ 失败 → status=vector_failed                  │
└──────────────────────────────────────────────────────┘
```

### 1.2 三库分工

| 存储 | 用途 | 数据形态 | 一致性级别 |
|------|------|---------|-----------|
| **MySQL** | 文档元数据 + 切片全文 | `knowledge_document` + `knowledge_document_chunk` | 强一致（事务） |
| **BM25 (Lucene)** | 关键词检索 | 磁盘索引 `data/bm25_index/{userId}/` | 与 MySQL 同事务 |
| **ChromaDB** | 向量语义检索 | Embedding 向量 + metadata | 最终一致（异步+重试+对账） |

### 1.3 关键类职责

| 类 | 文件 | 职责 |
|----|------|------|
| `DocumentProcessor` | `rag/DocumentProcessor.java` | 上传入口，编排解析→切片→去重→写入全流程 |
| `RagChunker` | `rag/RagChunker.java` | 结构感知的重叠切片算法 |
| `MarkdownBlockParser` | `rag/MarkdownBlockParser.java` | Markdown 结构解析（标题/代码/表格/列表/段落） |
| `TextChunker` | `rag/TextChunker.java` | 通用分隔符递归切片 |
| `Md5Store` | `rag/Md5Store.java` | 内存+文件双写 MD5 去重记录 |
| `VectorStoreService` | `rag/VectorStoreService.java` | MySQL+BM25 写入、ChromaDB 删除、降级检索 |
| `DocumentTaskExecutor` | `rag/DocumentTaskExecutor.java` | ChromaDB 异步写入（嵌入+重试+状态回调） |
| `Bm25Service` | `rag/Bm25Service.java` | Lucene BM25 索引读写 |
| `ChromaCleanupScheduler` | `rag/ChromaCleanupScheduler.java` | ChromaDB 残留数据对账清理 |
| `KnowledgeService` | `knowledge/service/KnowledgeService.java` | 上传/删除/重试 API 层 |

---

## 2. Tika 文档解析

### 2.1 入口逻辑

`DocumentProcessor.extractText(File, String)` (line 157-162):

```java
private String extractText(File file, String originalFilename) throws Exception {
    if (isPdf(originalFilename)) {
        return extractPdfTextByPage(file, originalFilename);
    }
    return tika.parseToString(file);
}
```

**格式分发策略：**
- `.pdf` 走 PDFBox 逐页提取（保留页码标记）
- `.docx`, `.txt`, `.md`, `.html` 等其他格式走 Tika `parseToString`

### 2.2 PDF 逐页提取（PDFBox）

`extractPdfTextByPage` (line 169-202):

```java
// 核心参数
stripper.setSortByPosition(true);  // 按视觉位置排序，非 PDF 内部流顺序

// 逐页循环
for (int page = 1; page <= pageCount; page++) {
    stripper.setStartPage(page);
    stripper.setEndPage(page);
    String pageText = stripper.getText(document);
    if (pageText == null || pageText.isBlank()) {
        continue;  // 跳过空白页
    }
    content.append("\n[Page ").append(page).append("/").append(pageCount).append("]\n")
           .append(pageText.strip()).append('\n');
}
```

**页码标记 `[Page N/M]` 是关键载体：**
- `MarkdownBlockParser` 解析它生成 `PAGE_BREAK` 块
- `RagChunker` 通过它给每个切片标注 `pageStart` / `pageEnd`
- 下游 `DocumentBlock` 和 `KnowledgeDocumentChunk` 都保留页码信息

### 2.3 Tika 通用解析

```java
private final Tika tika = new Tika();
// 构造函数中：
this.tika.setMaxStringLength(Integer.MAX_VALUE);  // 不截断文本
```

依赖 `tika-parsers-standard-package` 2.9.2，支持：
- `.docx` → Apache POI
- `.html` → Tika HTML parser
- `.txt` / `.md` → 纯文本
- 其他常见办公文档格式

### 2.4 异常处理

```java
catch (Exception e) {
    progressCallback.accept("error", originalFilename + ": " + e.getMessage());
    log.error("Failed to process file {}: {}", originalFilename, e.getMessage(), e);
    return CompletableFuture.failedFuture(e);
}
```

**没有按格式重试**：任何解析异常直接导致整个文件处理失败。SSE 事件中向前端推送 `"error"` 事件。

### 2.5 与聊天附件解析的区别

聊天附件走另一条路径 `FileExtractorService`，有 **5000 字符截断**：

```java
// FileExtractorService.java
private static final int MAX_TEXT_LENGTH = 5000;
// ...
if (text.length() > MAX_TEXT_LENGTH) {
    text = text.substring(0, MAX_TEXT_LENGTH) + "...(内容过长已截断)";
}
```

知识库解析不走这条路径，**不截断**。

---

## 3. 重叠切片处理

### 3.1 配置参数

`application.yml`:
```yaml
chroma:
  chunk-size: 500
  chunk-overlap: 80
```

### 3.2 切片算法（结构感知，扁平化）

`RagChunker.splitKnowledge()` (line 25-46) 是两阶段流水线：

```
原始文本
  │
  ▼
阶段一：MarkdownBlockParser.parse(text, isPdf)
  └─ 解析为 DocumentBlock 列表
     （HEADING / PARAGRAPH / CODE / TABLE / LIST / PAGE_BREAK）
  │
  ▼
阶段二：buildKnowledgeChildren(blocks, chunkSize, chunkOverlap)
  └─ 在扁平 block 流上直接切分：
     - 普通文本按 chunkSize/overlap 累积，满则切
     - 代码/表格/列表块尽量整块保留（≤ chunkSize*2 或 1200 chars）
     - 超长块（> chunkSize*2）强制用 TextChunker 切开
     - 切片间用 overlapText 携带重叠内容
     - HEADING 不再触发分组，直接当普通文本并入当前段
  │
  ▼
 直接输出扁平 RagChunk 列表
  - content = piece.text().strip()（干净正文，无元数据前缀）
  - metadata（文件名/页码）在检索时由 joinKnowledgeChunks 动态拼接
```

**设计变更**（从旧版 Parent/Child 架构重构为扁平化）：
- 移除了 `buildKnowledgeSections()` — 不再按标题分 Section
- 移除了 `compactKnowledgeChildren()` — 不再合并小碎片
- 移除了 Parent/Child 分组 — 不再有 `parentIndex` / `parentId`
- `RagChunk` 不再有 `parentIndex` / `parentId` 字段
- `KnowledgeDocumentChunk` 不再存 `sectionPath` / `parentId`
- 上下文扩展降级为 **neighbor-only**（hitIndex ± 1），限制 3000 chars

### 3.3 结构块保持逻辑（Keep Together）

```java
// buildKnowledgeChildren 内判断逻辑
private static boolean shouldKeepKnowledgeBlockTogether(String contentType, String text, int targetChars) {
    if ("code".equals(contentType) || "table".equals(contentType) || "list".equals(contentType)) {
        return text.length() <= Math.max(targetChars * 2, 1200);
    }
    return false;
}
```

**代码/表格/列表块 ≤ max(chunkSize*2, 1200) 时完整保留**，不切断。超过上限才强制用 `TextChunker.split` 切开。普通文本无此保护，按 targetChars 正常累积。

### 3.4 重叠携带机制

```java
// line 250-257
private static String overlapText(StringBuilder current, int overlap) {
    if (overlap <= 0) return "";
    String value = current.toString().strip();
    int start = Math.max(0, value.length() - overlap);
    return value.substring(start);
}
```

当一个 child 满了触发切割时，从当前 buffer 末尾取 `overlap` 长度的文本，**携带到下一个 child 的开头**。确保跨切片边界的信息不丢失。

### 3.5 切片内容格式

**存储时（RagChunker）**：content 和 retrievalText 均为干净正文，不带元数据前缀。

```java
chunk.setContent(piece.text().strip());
chunk.setRetrievalText(piece.text());
```

文件名/页码/章节等元数据以独立字段存储（`pageStart`, `pageEnd`, `contentType`），不预先烧进存储内容。

**检索时（VectorStoreService）**：元数据由 `joinKnowledgeChunks()` 动态拼接前缀。

```java
// VectorStoreService.joinKnowledgeChunks()
String label = buildKnowledgeSourceLabel(originalFilename, chunk.getPageStart(), chunk.getPageEnd());
// label 格式: "[文件: xxx.pdf] [页码: 12-14]\n"
sb.append(label + chunk.getContent());
```

**设计优势**：存储内容干净，检索时按需附加来源标签，避免元数据前缀污染 Embedding 语义向量。

### 3.6 切片质量校验

```java
// line 318-338
static List<String> validate(List<RagChunk> chunks, int chunkSize, boolean requirePage) {
    // 检查项：
    // 1. chunks 不能为空
    // 2. 每个 chunk 的 content 不能为空/空白
    // 3. 每个 chunk 不超过 maxContentChars = max(chunkSize*4, 1000)
    // 4. PDF 文档每个 chunk 必须有 pageStart（requirePage=true）
}
```

校验结果仅 **warn 日志记录**，不阻塞处理流程。

### 3.7 笔记切片（独立路径，仍保留层级）

笔记走 `RagChunker.splitNote()`，策略不同且保留更多结构：
- 笔记 ≤ 600 chars: 单切片（不做结构解析）
- 笔记 > 600 chars: 按 Markdown 标题分 Section，每 Section 再用 `TextChunker` 切
- 笔记切片间建立 `previousChunkId` / `nextChunkId` 邻居链
- 笔记检索时的上下文扩展走 `section`/`neighbor` 两级（知识库仅 neighbor）

### 3.8 TextChunker: 通用分隔符递归切片

```java
// TextChunker.java
// 分隔符优先级（从粗到细）：
"\n\n" → "\n" → "。" → "！" → "？" → "." → "!" → "?" → "；" → ";" → "，" → ","
```

递归尝试每个分隔符，找到第一个能把文本切成 2+ 段的即停止。每段递归继续切。最后 fallback 到固定长度子串。

---

## 4. MD5 去重

### 4.1 Hash 计算

`DocumentProcessor.computeMd5(File)` (line 204-219):

```java
public String computeMd5(File file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("MD5");
    try (InputStream is = new FileInputStream(file)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
    }
    byte[] digest = md.digest();
    StringBuilder sb = new StringBuilder();
    for (byte b : digest) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

**Hash 对象：原始文件字节。** 同一文件重命名后上传，MD5 相同，视为重复。不是对提取文本或切片做 hash。

### 4.2 MD5 存储（Md5Store）

双写策略：**内存 ConcurrentHashMap + 文件持久化**。

```
内存结构：
ConcurrentHashMap<userId, Map<md5, "filename|originalFilename">>

文件路径：
data/md5_hex_store/md5_hex_store.txt

文件格式（每行）：
md5|filename|originalFilename|userId
```

启动时 `loadFromFile()` 加载，每次变更后 `saveToFile()` 全量覆写。

### 4.3 去重决策链（三道防线）

`DocumentProcessor.processFile()` (line 42-80):

```
防线一：内存 MD5 检查
  md5Store.exists(md5, userId)
    ├─ 不存在 → 继续处理
    └─ 存在 → 进入防线二

防线二：MySQL 验证（双重确认）
  vectorStoreService.hasKnowledgeDocument(userId, md5)
    ├─ 存在 → progressCallback("skipping")，return（真正跳过）
    └─ 不存在 → 说明向量数据丢失，删除残留 MD5 记录，重新处理

防线三：DB 唯一约束（并发竞态）
  MySQL uk_user_md5 UNIQUE(user_id, md5)
  └─ 并发上传同一文件 → DataIntegrityViolationException
     └─ 检查消息是否含 "uk_user_md5"
        ├─ 是 → 当作 skipping
        └─ 否 → rethrow 作为异常失败
```

### 4.4 MD5 与 MySQL 对账

`VectorStoreService.getUserDocuments()` (line 844-888):

遍历 MD5 记录，找出 MySQL 中不存在的记录，以 `status="unknown"`, `chunkCount=0` 返回给前端。前端可据此展示 "MD5 记录残留" 状态。

### 4.5 MD5 记录清理时机

| 操作 | 清理方式 |
|------|---------|
| 文档删除 | `md5Store.deleteByMd5(md5, userId)` |
| 用户清空知识库 | `md5Store.deleteByUser(userId)` |
| MySQL 文档不存在但 MD5 存在 | `cleanMd5ByFilename()` 按文件名清理 |
| MD5 存在但 MySQL 不存在（处理时发现） | 自动删除 MD5 后重新处理 |

---

## 5. 状态机

### 5.1 文档状态定义

`KnowledgeDocument.status` — 字符串字段，取值：

| 状态 | 含义 | 设置位置 |
|------|------|---------|
| `processing` | MySQL+BM25 已写入，ChromaDB 写入中 | `addKnowledgeDocumentChunks()`, `retryVectorization()` |
| `completed` | 三库全部写入成功 | `DocumentTaskExecutor.markVectorCompleted()` |
| `vector_failed` | ChromaDB 写入失败（MySQL+BM25 正常） | `DocumentTaskExecutor.markVectorFailed()` |
| `unknown` | MD5 记录存在但 MySQL 无对应行（前端展示用，不存 DB） | `getUserDocuments()` 合成 |

### 5.2 状态流转图

```
上传/重试
    │
    ▼
┌──────────────┐    ChromaDB 写入成功    ┌─────────────┐
│  processing  │ ───────────────────────→ │  completed  │
└──────────────┘                          └─────────────┘
    │
    │ ChromaDB 写入失败 / 不可用
    ▼
┌───────────────┐     POST /retry-vectorization    ┌──────────────┐
│ vector_failed │ ─────────────────────────────────→ │  processing  │
└───────────────┘                                    └──────────────┘
```

### 5.3 重试向量化（retryVectorization）

```java
// VectorStoreService.java line 733-766
public boolean retryVectorization(String docId) {
    // 1. 只允许 vector_failed 状态重试
    if (!"vector_failed".equals(doc.getStatus())) {
        return false;  // 上层抛 BusinessException
    }

    // 2. 先清理 ChromaDB 旧数据（防止残留）
    deleteFromChromaByDocId(docId);

    // 3. 重置为 processing
    doc.setStatus("processing");
    documentRepository.save(doc);

    // 4. 重新异步写入
    documentTaskExecutor.writeToChromaAsync(...);
}
```

**防重入保障**：`KnowledgeService.retryVectorization()` 在状态不是 `vector_failed` 时抛 `BusinessException`。

### 5.4 ChromaCleanupTask 状态机（删除对账）

`ChromaCleanupTask` (entity, line 17-51):

```
删除操作 ChromaDB 失败
    │
    ▼
┌──────────┐    每 5 分钟重试     ┌──────────┐
│ pending  │ ───────────────────→ │  (删除)  │  成功 → 删除任务记录
└──────────┘                      └──────────┘
    │                                  │
    │ 失败 retryCount++                │ retryCount >= 10
    │ (仍为 pending)                   ▼
    │                            ┌──────────┐    每天 3am 清理 >7天
    │                            │  failed  │ ───────────────────→ 删除
    │                            └──────────┘
```

`ChromaCleanupScheduler`:
- `cleanupFailedChromaDeletions()`: **每 5 分钟**执行，处理所有 `pending` 任务
- `cleanupOldFailedTasks()`: **每天凌晨 3 点**，删除 `failed` 且超过 7 天的任务

### 5.5 markVectorCompleted 的幂等保护

```java
// DocumentTaskExecutor.java line 200-207
private void markVectorCompleted(String docId) {
    documentRepository.findById(docId).ifPresent(doc -> {
        if (!"completed".equals(doc.getStatus())) {  // 幂等 guard
            doc.setStatus("completed");
            documentRepository.save(doc);
        }
    });
}
```

---

## 6. MySQL + BM25 + ChromaDB 三库一致性

### 6.1 写入路径（强一致 MySQL+BM25，最终一致 ChromaDB）

```
DocumentProcessor.processFile()
    │
    ├─ ① VectorStoreService.addKnowledgeDocumentChunks()  ← @Transactional
    │      ├─ MySQL: INSERT knowledge_document (status=processing)
    │      ├─ MySQL: INSERT knowledge_document_chunk * N
    │      ├─ BM25:  bm25Service.addDocument() * N (同一事务内)
    │      └─ 返回 docId
    │
    ├─ ② md5Store.save()  ← 内存+文件，不在事务内
    │
    └─ ③ DocumentTaskExecutor.writeToChromaAsync()  ← 异步，不在事务内
           └─ documentExecutor 线程池执行
              ├─ batchEmbed() → Embedding API (batch size=3, retry 5次)
              ├─ addBatchToChromaWithRetry() → ChromaDB (retry 3次)
              ├─ 成功 → markVectorCompleted()
              └─ 失败 → markVectorFailed()
```

### 6.2 为何 MySQL 和 BM25 在同一事务

`VectorStoreService.addKnowledgeDocumentChunks` 带 `@Transactional`：
- MySQL JPA 写入受 Spring 事务管理
- BM25 Lucene 写入在 `synchronized` 方法中 `writer.commit()`

**已知限制**：Lucene `commit()` 后 Spring 事务 abort 无法回滚 BM25。后续删除逻辑作为补偿。

### 6.3 ChromaDB 异步写入细节

`DocumentTaskExecutor.writeToChromaSync()` (line 81-171):

```
写入策略：
  - 跳过空切片（content == null || isBlank）→ log warn 并继续
  - batch_size = 3
  - 每批：先 Embedding API 调用（5 次重试，线性退避 1s*attempt）
  - 再 ChromaDB write（3 次重试，线性退避 1s*attempt）
  - 批次间 sleep 300ms（防止 API 限流）
  - 全部成功后 markVectorCompleted()
  - 任何失败后 markVectorFailed()

嵌入重试策略：
  1. 批量 embed: 5 次重试，每次退避 1s/2s/3s/4s/5s
  2. 检查返回 size 是否与输入 texts 数量一致（防 API bug）
  3. 5 次失败后降级为逐条 embed（依旧有 retry）
  4. 还是失败 → 整个文件 vector_failed
```

### 6.4 删除路径与一致性分析

#### 6.4.1 删除顺序（MySQL 优先 + cleanupTask 独立事务）

设计原则：**MySQL 先删（失败零副作用），ChromaDB/BM25 后删（失败各自写独立事务的 cleanupTask）。**

```
KnowledgeDeleteCoordinator.deleteKnowledgeByFilename() — 编排层，不加 @Transactional
    │
    ├─ ① VectorStoreService.deleteMySQLOnly()  ── @Transactional 独立事务
    │      ├─ 悲观行锁 SELECT ... FOR UPDATE
    │      ├─ 读取 chunks 到内存，构造 BM25 key 列表
    │      ├─ DELETE knowledge_document（CASCADE 删 chunks）
    │      └─ commit → 返回 DeleteContext；rollback → 抛异常，②③④ 不执行
    │
    ├─ ② vectorStoreService.deleteFromChromaByDocId()  ── REST 调用，无事务
    │      └─ 失败 → cleanupTaskService.saveChromaCleanup()  ── @Transactional(REQUIRES_NEW)
    │
    ├─ ③ bm25Service.deleteDocuments()  ── Lucene commit，异常向外抛
    │      └─ 失败 → cleanupTaskService.saveBm25Cleanup()  ── @Transactional(REQUIRES_NEW)
    │
    └─ ④ md5Store.deleteByMd5()  ── 内存+文件
```

**事务边界关键设计：**
- `deleteMySQLOnly` 是独立 `@Transactional`，commit 后 Coordinator 才执行后续步骤
- `CleanupTaskService` 两个方法都是 `@Transactional(propagation = REQUIRES_NEW)`，即使协调器线程后续异常也不回滚
- `Bm25Service.deleteDocuments` 已修复：catch IOException 改为 rethrow RuntimeException，调用方可感知失败

#### 6.4.2 三端故障组合分析（8 场景）

关键前提：
- 步骤①（MySQL delete）失败 → 抛异常，②③④ 不执行，ChromaDB 和 BM25 完全未被触碰
- cleanupTask 通过 `REQUIRES_NEW` 独立事务持久化，不受任何回滚影响
- BM25 delete 异常已改为向外抛，调用方可 catch 并写 cleanupTask

| # | Chr | BM25 | MySQL | 执行路径 | 结果 |
|---|-----|------|-------|---------|------|
| 1 | 失败 | 成功 | 成功 | ② saveChromaCleanup(REQUIRES_NEW) | Chr 残留，scheduler 每 5min 自动补删 |
| 2 | 成功 | 失败 | 成功 | ③ saveBm25Cleanup(REQUIRES_NEW) | BM25 残留，scheduler 自动补删 |
| 3 | - | - | 失败 | ① rollback，②③④ 不执行 | **零副作用，用户重试即可** |
| 4 | 失败 | 失败 | 成功 | ②③ 各写 task | Chr+BM25 各有 task，scheduler 自动补删 |
| 5 | - | - | 失败 | ① rollback，②③④ 不执行 | **零副作用** |
| 6 | - | - | 失败 | ① rollback，②③④ 不执行 | **零副作用** |
| 7 | - | - | 失败 | ① rollback，②③④ 不执行 | **零副作用** |
| 8 | 成功 | 成功 | 成功 | 全部执行 | **完美一致** |

#### 6.4.3 一致性总结

| 故障点 | ChromaDB 补偿 | BM25 补偿 | 结果 |
|--------|-------------|----------|------|
| 仅 ChromaDB 失败 | cleanupTask 独立持久化 | - | 自动恢复 |
| 仅 BM25 失败 | - | cleanupTask 独立持久化 | 自动恢复 |
| 仅 MySQL 失败 | -（未触碰） | -（未触碰） | 零副作用，重试 |
| 双失败 | cleanupTask 独立持久化 | cleanupTask 独立持久化 | 自动恢复 |

**全部 8 种场景均可达到最终一致。** 核心保障来自两点：(1) MySQL 先执行，失败则后续全部跳过；(2) cleanupTask 用 `REQUIRES_NEW` 独立事务，永不丢失。

#### 6.4.4 实现清单

**前置修复：Bm25Service 吞异常问题**

```java
// 改后：commit 成功才清 metadata，异常向外抛
public synchronized void deleteDocuments(String userId, Collection<String> docIds) {
    // ... IndexWriter + commit ...
    catch (IOException e) {
        throw new RuntimeException("BM25 delete failed", e);  // 不再吞异常
    }
    // commit 成功才清内存 metadata
}
```

**CleanupTaskService（新建）**

```java
@Service
public class CleanupTaskService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveChromaCleanup(String docId, String userId, String collectionName) { ... }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBm25Cleanup(String docId, String userId, List<String> bm25Keys) { ... }
}
```

**VectorStoreService — 拆出 MySQL 优先删除**

```java
@Transactional
public DeleteContext deleteMySQLOnly(String userId, String filename) {
    // 悲观锁读 → 读 chunks 构造 bm25Keys → DELETE → commit → 返回 ctx
}
```

**KnowledgeDeleteCoordinator（新建，编排层）**

```java
@Service
public class KnowledgeDeleteCoordinator {
    public void deleteKnowledgeByFilename(String userId, String filename) {
        DeleteContext ctx = vectorStoreService.deleteMySQLOnly(userId, filename);
        if (ctx == null) return;
        if (!vectorStoreService.deleteFromChromaByDocId(ctx.docId()))
            cleanupTaskService.saveChromaCleanup(...);
        try { bm25Service.deleteDocuments(...); }
        catch (Exception e) { cleanupTaskService.saveBm25Cleanup(...); }
        md5Store.deleteByMd5(...);
    }
}
```

**其他改动：**
- `ChromaCleanupTask` 加 `bm25KeysJson` 字段（TEXT）
- `ChromaCleanupScheduler` 加 `"bm25"` 类型清理分支
- `KnowledgeService` 调用方切为 `knowledgeDeleteCoordinator.deleteKnowledgeByFilename(...)`

| 文件 | 操作 | 行数 |
|------|------|------|
| `Bm25Service.java` | catch 改为 rethrow | ~3 行 |
| 新建 `CleanupTaskService.java` | 两个 REQUIRES_NEW 方法 | ~30 行 |
| 新建 `KnowledgeDeleteCoordinator.java` | 编排方法 | ~35 行 |
| `VectorStoreService.java` | 拆出 `deleteMySQLOnly`，删旧方法 | ~25 行改 |
| `ChromaCleanupTask.java` | 加 `bm25KeysJson` 字段 | ~3 行 |
| `ChromaCleanupScheduler.java` | 加 BM25 清理分支 | ~15 行 |
| `KnowledgeService.java` | 调用方切 coordinator | ~1 行 |

共约 110 行，净增 2 个类，改 5 个文件，不改 API，不引入新依赖。

### 6.5 并发删除保护

```java
// KnowledgeDocumentRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT d FROM KnowledgeDocument d WHERE d.userId = :userId AND d.filename = :filename")
Optional<KnowledgeDocument> findByUserIdAndFilenameForUpdate(
    @Param("userId") String userId,
    @Param("filename") String filename);
```

**悲观写锁**保证同一文档的并发删除串行化。第二个请求会在第一个事务提交前阻塞。

### 6.6 笔记侧的 afterCommit 模式

`NoteService` 将向量删除推迟到事务提交后：

```java
// NoteService.java line 294-304
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        vectorStoreService.deleteNoteVector(noteId, userId);
    }
});
```

**原因**：笔记删除是业务事务的一部分，向量删除不应回滚业务事务。afterCommit 确保"笔记已删完"才删向量。

### 6.7 ChromaDB Collection ID 的 Redis 缓存

```
缓存 Key:  chroma:collection:id:{collectionName}
TTL:       24 小时
失效场景:
  - 写操作失败后重试时清除 → 重新获取
  - ChromaDB 返回 InvalidCollection → 自动清除缓存
```

`VectorStoreService.getCollectionId()` (line 653-697):
1. 先查 Redis 缓存
2. Redis miss → 调 ChromaDB REST API `GET /api/v1/collections/{name}`
3. 解析响应 JSON 的 `id` 字段
4. 写入 Redis，TTL 24h

### 6.8 检索时降级（读路径一致性保障）

`VectorStoreService.searchKnowledge()` (line 768-793):

```java
if (chromaAvailable) {
    try {
        return searchChroma(knowledgeStore, queryEmbedding, userId, topK, ...);
    } catch (Exception e) {
        // 指定文档检索时 Chroma 失败 → 降级到 MySQL 逐条比对
        if (!selectedIdentifiers.isEmpty()) {
            return searchKnowledgeFromMySQL(userId, queryEmbedding.vector(), topK, selectedIdentifiers);
        }
        throw e;  // 全量检索失败则上抛
    }
} else {
    // ChromaDB 启动时就不可用 → 全程 MySQL 降级
    return searchKnowledgeFromMySQL(userId, queryEmbedding.vector(), topK, selectedIdentifiers);
}
```

**MySQL 降级方案** (`searchKnowledgeFromMySQL`, line 799-840):
1. 从 MySQL 查询该用户所有文档的所有切片
2. 逐条调用 Embedding API 获取向量
3. 内存计算 `cosineSimilarity(queryVector, chunkVector)`
4. 按 similarity 排序，取 topK

**代价**：O(N) Embedding API 调用，速度远慢于 ChromaDB 的 HNSW 索引。但**保证可用**。

### 6.9 笔记操作为何先写 BM25

`VectorStoreService.addNoteVector()` (line 117-167) 在 `@Transactional` 内：
1. MySQL NoteChunk
2. BM25 addDocument（同一事务）
3. ChromaDB 异步（不阻塞事务返回）

笔记更新 = deleteNoteVector + addNoteVector，两者在同一事务内。

---

## 7. 异常场景与容错机制

### 7.1 并发上传同一文件

**场景**：两个请求同时上传 `a.pdf`，MD5 一样。

**处理**：
1. 请求 A：MD5 内存检查 ×，进入 MySQL 写入
2. 请求 B：MD5 内存检查 ×，进入 MySQL 写入
3. 哪个先 commit 哪个成功，另一个 hit `uk_user_md5` → `DataIntegrityViolationException`
4. `DocumentProcessor` 检查异常消息含 `"uk_user_md5"` → 当作 skipping

**关键代码** (`DocumentProcessor` line 89-98):
```java
catch (DataIntegrityViolationException e) {
    if (e.getMessage() != null && e.getMessage().contains("uk_user_md5")) {
        log.info("文档已在并发处理中上传，跳过: {}", originalFilename);
        progressCallback.accept("skipping", originalFilename);
        return CompletableFuture.completedFuture(null);
    }
    // 其他完整性异常 → rethrow
}
```

### 7.2 ChromaDB 不可用

**启动时**：
```java
// VectorStoreService 构造函数
try {
    this.knowledgeStore = ChromaEmbeddingStore.builder()...
    this.chromaAvailable = true;
} catch (Exception e) {
    this.knowledgeStore = null;  // chromaAvailable = false
}
```

**写入时**：`DocumentTaskExecutor` 标记 `vector_failed`，MySQL+BM25 正常可用。

**检索时**：降级到 MySQL 逐条 Embedding + 内存 cosine 相似度。

### 7.3 嵌入 API 不稳定

5 次重试 + 线性退避 (`1s × attempt`)，失败后降级为逐条 embed，再失败标记 `vector_failed`。

### 7.4 PDF 空白页

```java
if (pageText == null || pageText.isBlank()) {
    log.debug("PDF page has no extractable text: ...");
    continue;  // 跳过，不生成 [Page N/M] 标记
}
```

空白页不产生 `[Page N/M]` 标记，后续页码仍然是实际有内容的页码。

### 7.5 切片全空

`RagChunker.validate()` 发现 `chunks.isEmpty()` 会 warn 日志。`DocumentTaskExecutor` 发现 `content.isBlank()` 跳过该切片不写 ChromaDB。但 MySQL 和 BM25 中该切片仍然存在（含空 content）。

### 7.6 MySQL 事务回滚后 BM25 残留

BM25 写入在 `synchronized` 方法中执行 `commit()`，不在 Spring 事务管控内。如果后续 MySQL 操作失败导致事务回滚，BM25 索引中会有残留。**当前代码没有自动回滚 BM25 的机制。** 这是设计上的已知 tradeoff：
- 删除路径会按 md5_index key 清理 BM25
- 事务很少失败（主要失败来源是 ChromaDB，而那一步在事务外）

### 7.7 删除时 ChromaDB/BM25 失败

删除顺序已优化为 MySQL 优先：MySQL 先删成功后才动 ChromaDB 和 BM25。ChromaDB 或 BM25 删除失败时，各自通过 `CleanupTaskService`（`REQUIRES_NEW` 独立事务）写入 cleanupTask，由 `ChromaCleanupScheduler` 每 5 分钟自动补删。详细 8 场景分析见 [6.4.2](#642-三端故障组合分析8场景)。

### 7.8 MD5 文件持久化失败

`Md5Store.saveToFile()` 异常只 log warn，不影响处理流程。下次重启时从磁盘加载的可能是过期数据。但内存中的 ConcurrentHashMap 仍然是最新的，只是重启后会丢失。

### 7.9 超大文档

`tika.setMaxStringLength(Integer.MAX_VALUE)` 不截断提取文本。但超大文档的切片数量可能很大（每个 child 约 500 chars），ChromaDB 写入耗时较长。批次间 300ms sleep + batch=3 的方式可能导致数分钟写入时间。`CHROMA_TIMEOUT = 2分钟` 是整个 ChromaDB HTTP 的超时，不是整体写入超时。

---

## 8. 简历学习重点指南

> 以下根据你的简历描述逐条拆解，标注面试必问核心点和你需要深入理解的细节。

### 8.1 "Tika 解析" — 你需要掌握

**⭐⭐⭐ 核心原理（必问）：**
- Tika 的 `AutoDetectParser` 内部如何根据 magic bytes / MIME type 选择 Parser
- PDFBox vs Tika 自带的 PDFParser：为什么选 PDFBox？因为需要按页提取并插入页码标记，Tika 的 PDF 解析不保留页面边界
- `setSortByPosition(true)` 的含义：PDF 内部文本顺序 ≠ 视觉阅读顺序

**⭐⭐ 异常场景：**
- PDF 是扫描件（图片型 PDF）：PDFBox 提取为空，`continue` 跳过。如何处理？OCR 集成（本项目没做，但面试会被问"你遇到了怎么解决"）
- 加密 PDF：PDFBox 会抛异常，`processFile` 直接 failure
- Tika 对 `.docx` 的表格/图片处理能力边界

**⭐ 刷面试指南：**
- 读一遍 Tika 官方文档的 Parser 列表，知道它能处理哪些格式
- 了解 PDFBox 的 `PDFTextStripper` vs `PDFTextStripperByArea` 的区别

### 8.2 "重叠切片" — 你需要掌握

**⭐⭐⭐ 核心原理（必问）：**
- **为什么需要重叠？** 跨切片边界的信息不丢失。举例："见上文所述" 的上文可能在上一个切片里
- **你项目的做法是结构感知切片**，不是 naive 滑动窗口。这是核心亮点，面试官会问你区别
- **扁平化设计**：不再按标题分 Section，不再有 Parent/Child 层级，所有 block 在一个扁平流上按 chunkSize/overlap 累积切分。HEADING block 当普通文本处理，避免标题识别错误破坏切片边界

**⭐⭐ 关键技术决策（加分项）：**
- **为什么去掉 Parent/Child？** 标题识别不准时会被错误地当作 Section 边界，导致父子分组混乱。扁平化消除这个不稳定因素
- 代码/表格/列表 Keep Together → 保持结构完整性，否则切开的 SQL/代码片段毫无意义
- **存储内容干净，元数据动态拼接**：content 只存纯文本，`[文件名]`/`[页码]` 在检索时由 `joinKnowledgeChunks` 加上。避免元数据前缀污染 Embedding 语义向量
- **知识库上下文扩展仅 neighbor（hitIndex ± 1，限 3000 chars）**，笔记仍保留 section 级扩展

**⭐⭐ 重叠计算的细节：**
- `overlapText()` 取 `value.length() - overlap` 到末尾，不是简单最后 N 个字符
- 为什么 `overlap` 先被 clamp 确保不大于 `chunkSize-1`？防止单个字符无限重叠循环

**⭐ 刷面试指南：**
- 对比 LangChain 的 `RecursiveCharacterTextSplitter` 和你项目的实现，说出优缺点
- 了解 Embedding 模型的 max token 限制，理解为什么 chunk_size 不能太大
- 准备回答"为什么从 Parent/Child 改成扁平化"——核心是标题识别不可靠导致分组错误，扁平化更鲁棒

### 8.3 "MD5 去重" — 你需要掌握

**⭐⭐⭐ 核心原理（必问）：**
- **Hash 对象是原始文件字节**，不是提取文本。为什么？同一文件改名上传，内容一样，应该去重；但如果是对文本 hash，不同的排版格式可能导致文本不同但文件相同
- **三道防线**设计：内存检查 → MySQL 验证 → DB 唯一约束。每道防线解决什么问题？

**⭐⭐ 防线分析：**
- 内存检查：快速短路，避免每次查 DB
- MySQL 验证：防止内存数据丢失（重启/多实例）导致的误判
- DB 唯一约束：防止并发竞态（两个请求同时过前两道防线）

**⭐⭐ 并发竞态分析（高分题）：**
- `md5Store.exists()` 是 `synchronized`，但只是单机锁，多实例无效
- 真正的并发保障是 `uk_user_md5` 唯一约束
- `DataIntegrityViolationException` 消息嗅探 `"uk_user_md5"` 是一种 pragmatic approach，不是优雅设计，但面试时能说清楚就是加分项

**⭐ 刷面试指南：**
- 了解 MD5 碰撞问题（已有理论攻击，但文件去重场景够用）
- 如果面试官问 "为什么不用 SHA-256"，回答：MD5 够快，8KB buffer 流式计算内存友好，文件去重不需要密码学安全

### 8.4 "状态机" — 你需要掌握

**⭐⭐⭐ 核心原理（必问）：**
- 三个核心状态：`processing` → `completed` / `vector_failed`
- 状态机的作用：**解耦同步写入和异步写入**。MySQL+BM25 是同步的，ChromaDB 是异步的，状态机桥接两者，让前端知道什么时候 ChromaDB 就绪

**⭐⭐ 关键设计点：**
- `retryVectorization` 的防重入：只允许 `vector_failed` 状态，不允许 `completed` 重试
- 重试时先 `deleteFromChromaByDocId` 清理旧数据：防止 ChromaDB 累积重复向量
- `markVectorCompleted` 的幂等 guard：`if (!"completed".equals(doc.getStatus()))` 防止重复写

**⭐⭐ ChromaCleanupTask 的对账状态机：**
- 为什么需要 10 次重试上限而不是无限重试？防止永久性失败（如 ChromaDB 迁移了、collection 被删了）占满任务队列
- 7 天过期清理：避免 `failed` 任务表无限增长
- 5 分钟间隔和每天 3am 清理：错峰执行，减少 ChromaDB 压力

**⭐ 刷面试指南：**
- 画图：状态流转 + 触发条件 + 每个状态下的系统行为
- 理解 "最终一致性" 和 "对账" 的概念

### 8.5 "MySQL、BM25、ChromaDB 数据一致" — 你需要掌握

**⭐⭐⭐ 核心原理（最高频问题）：**

**写入一致性**：
- MySQL 和 BM25 强一致（同一事务），ChromDB 最终一致（异步+重试）
- **为什么不全同步？** ChromaDB HTTP 调用不在 Spring 事务管理范围内，且 Embedding API 调用慢（每批 1-3s），放在事务内会导致长事务锁表
- **为什么不全异步？** MySQL 写入失败需要立即反馈给用户，不能让用户等 30s 才发现"上传失败"

**删除一致性（面试高分重点）**：
- 删除顺序：MySQL 优先（独立事务）→ ChromaDB（REST）→ BM25（Lucene）→ MD5
- 核心设计：MySQL 先删作为第一道屏障，失败则后续全部跳过，零副作用
- cleanupTask 用 `REQUIRES_NEW` 独立事务，即使后续步骤故障也不丢失对账记录
- 全部 8 种故障组合均可达到最终一致（ChromaDB/BM25 残留由 scheduler 每 5 分钟自动补删）
- 面试能把这 8 种场景推导出来 + 讲清 `REQUIRES_NEW` 为什么能保证 task 不丢失，是显著的区分度
- `@Lock(PESSIMISTIC_WRITE)` 防并发删除

**读一致性**：
- ChromaDB 不可用 → MySQL 降级
- 降级方案的代价：O(N) Embedding 调用，速度慢但保证可用

**⭐⭐ 关键架构权衡（高分题）：**

1. **分布式事务 vs 最终一致性**：三库跨三种存储引擎（关系型、全文搜索、向量），不可能用 XA 分布式事务。最终一致性 + 对账机制是业界标准做法。

2. **Lucene commit 不在 Spring 事务内**：是已知限制。如果面试官追问，回答方向：可以用 `TransactionSynchronization.afterCommit` 延迟 BM25 commit，或者用 WAL 机制。选不选看业务对一致性的容忍度。

3. **ChromaDB 异步写入失败后的补偿**：`vector_failed` 状态 + 重试 API。不是自动重试（避免循环失败），而是让用户/管理员主动触发。

**⭐ 刷面试指南：**
- 对比 Pinecone / Weaviate / Milvus 这些向量数据库的一致性模型
- 理解 CAP 理论在此场景的体现：选择了 AP（可用 + 分区容错），牺牲了 CP（强一致）
- 准备一句话总结："写入路径 MySQL+BM25 同步强一致，ChromaDB 异步最终一致+状态机补偿。删除路径 MySQL 优先+cleanupTask 独立事务，8 种故障组合全部可达最终一致。"

---

## 附录：关键文件索引

| 文件 | 内容 |
|------|------|
| `rag/DocumentProcessor.java` | 文档处理入口：Tika/PDFBox 解析、MD5 去重、流程编排 |
| `rag/RagChunker.java` | 结构感知重叠切片（Markdown 解析 + 扁平化切分）、质量校验 |
| `rag/MarkdownBlockParser.java` | Markdown 结构解析（标题/代码/表格/列表/段落/页码） |
| `rag/TextChunker.java` | 通用分隔符递归切片 + overlap 携带 |
| `rag/VectorStoreService.java` | 三库写入/删除/检索、MD5 对账、ChromaDB 降级 |
| `rag/DocumentTaskExecutor.java` | ChromaDB 异步写入、Embedding 重试、状态更新 |
| `rag/Bm25Service.java` | Lucene BM25 索引读写 |
| `rag/Md5Store.java` | MD5 内存+文件双重去重存储 |
| `rag/ChromaCleanupScheduler.java` | ChromaDB 残留数据定时对账清理 |
| `knowledge/entity/KnowledgeDocument.java` | 文档实体（含状态字段、`uk_user_md5` 约束） |
| `knowledge/entity/ChromaCleanupTask.java` | 清理任务实体（含重试次数、状态、最大重试上限） |
| `knowledge/service/KnowledgeService.java` | API 层：上传、删除、重试向量化 |
| `config/ApplicationProperties.java` | 配置：chunk-size、chunk-overlap、ChromaDB URL 等 |

---

*文档由代码分析自动生成，覆盖 `backend-java` 模块全部 RAG 知识库同步逻辑。*
*最后更新：2026-08-05（6.4 节重构为 MySQL 优先删除方案，8 场景全部一致；3 节扁平化切片）*
