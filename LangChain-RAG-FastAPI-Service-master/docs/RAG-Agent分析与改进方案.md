# RAG NoteBook Agent 架构分析与改进方案

## 一、项目现状分析

### 1.1 现有 Agent 架构

```
用户查询 → ClarifierService（意图澄清）
              ↓
         SupervisorService（任务拆分）
              ↓
    ┌────────┼────────┐
  子任务1  子任务2  子任务3   ← 并行执行
    └────────┼────────┘
              ↓
         WriterService（结果合成）
              ↓
         QualityReviewer（质量审查）
              ↓
           最终回答
```

### 1.2 已具备的 Agent 能力

| 能力 | 实现 | 代码位置 |
|------|------|----------|
| 意图澄清 | `ClarifierService` 判断查询是否清晰，模糊时引导用户 | `agent/ClarifierService.java` |
| 任务规划 | `SupervisorService` 将复杂查询拆分为 2-4 个子任务 | `agent/SupervisorService.java` |
| 工具调用 | `AgentTools` 提供 RAG 检索、笔记搜索、创建笔记等 6 个工具 | `agent/AgentTools.java` |
| 多轮推理 | `AgentService.processWithFunctionCalling` 最多 3 轮工具调用循环 | `agent/AgentService.java` |
| 并行执行 | `executePipeline` 子任务并行执行 + `WriterService` 合成 | `agent/AgentService.java` |
| 质量审查 | `QualityReviewer` 审查回答质量，不达标时重试 | `rag/QualityReviewer.java` |
| 上下文管理 | `ContextManager` 滑动窗口 + 摘要压缩 | `agent/ContextManager.java` |

### 1.3 现有工具清单

| 工具名 | 功能 | 类型 |
|--------|------|------|
| `ragSummary` | 从知识库检索文档并生成摘要 | 读操作 |
| `searchNotes` | 搜索用户笔记 | 读操作 |
| `getNoteStats` | 获取笔记统计信息 | 读操作 |
| `getTodayReviews` | 获取今日复习笔记列表 | 读操作 |
| `markReviewed` | 标记笔记已复习 | 写操作 |
| `createNote` | 创建新笔记 | 写操作 |
| `getRelatedNotes` | 查找相关笔记 | 读操作 |
| `whatTimeIsNow` | 获取当前时间 | 辅助 |

---

## 二、与真正 Agent 的差距

### 2.1 固定流水线 vs 自主决策

**现状问题：**

不管用户问什么，都走相同的 Clarifier → Supervisor → 并行执行 → Writer 流水线。这是写死的 pipeline，不是 Agent 在思考。

**示例对比：**

用户查询：*"帮我整理一下我上周关于机器学习的笔记，看看有没有遗漏的知识点，然后根据知识库补充一份学习计划"*

**现在的行为：**
1. Supervisor 拆分为 3 个子任务
2. 子任务 1：搜索笔记 → 返回结果
3. 子任务 2：检索知识库 → 返回结果
4. 子任务 3：生成学习计划 → 基于前两个结果生成
5. Writer 合成最终回答

**问题：** 子任务 1 的结果（发现了哪些笔记、遗漏了什么）无法影响子任务 2 的检索策略，子任务之间互不通信。

**真正的 Agent 行为：**
1. 搜索笔记 → 发现 5 篇相关笔记
2. **自主判断**"知识点 A 覆盖了，但知识点 B 没有"
3. **自主决定**去知识库检索知识点 B 的资料
4. 基于检索结果，**自己决定**要不要创建新笔记
5. 创建学习计划时，**自己判断**哪些内容要先复习
6. 如果检索结果不理想，**换个关键词再试**

### 2.2 工具太薄 — 只能"读"，不能"做"

**现状：** 6 个工具中 5 个是读操作，唯一能改变外部状态的只有 `createNote` 和 `markReviewed`。

**Agent 缺乏的工具：**

```java
// 笔记操作 — 现在只能创建，不能编辑和删除
editNote(noteId, diff)           // 编辑已有笔记
deleteNote(noteId)               // 删除笔记
mergeNotes(noteIds, title)       // 合并多篇笔记

// 外部信息获取 — 只能查自己的知识库
searchWeb(query)                 // 联网搜索补充知识
fetchUrl(url)                    // 抓取网页内容

// 执行能力 — 没有任何执行能力
executeCode(code, language)      // 跑一段代码验证想法
generateDiagram(data, type)      // 生成图表

// 通知与协作 — 没有主动交互
sendNotification(msg)            // 主动通知用户
scheduleReview(noteId, date)     // 主动安排复习时间

// 数据操作 — 只有简单的查询
queryDatabase(sql)               // 查数据库
callExternalAPI(url, params)     // 调外部 API
```

### 2.3 缺乏反思和纠错

**现状代码 (`AgentService:353-398`)：**

```java
for (int i = 0; i < 3; i++) {
    Response<AiMessage> chatResponse = chatModel.generate(messages, activeTools);
    AiMessage aiMessage = chatResponse.content();
    if (aiMessage.hasToolExecutionRequests()) {
        // 执行工具，结果直接加入 messages
    } else {
        return aiMessage.text(); // 直接返回
    }
}
```

**问题：** 如果 `ragSummary` 返回的结果不够好，Agent 不会反思"这个结果不理想，我换个搜索词再试"。它只是把工具结果原样塞给 LLM。

**应有的反思循环：**

```
思考 → 行动 → 观察结果 → 评估结果质量 → 决定下一步
                    ↑                    ↓
                    ←── 结果不好，换个策略 ←┘
```

### 2.4 没有持久记忆

**现状：** `ContextManager` 只做滑动窗口 + 摘要压缩，这是**短期记忆**，会话结束即丢失。

**缺乏的记忆类型：**

| 记忆类型 | 说明 | 示例 |
|----------|------|------|
| 用户画像 | 用户身份、角色、偏好 | "后端开发，3年经验，喜欢代码示例" |
| 任务记忆 | 历史任务和结果 | "上周帮用户整理过 RAG 笔记" |
| 偏好记忆 | 回答风格、格式偏好 | "喜欢结构化回答，不要太长" |
| 知识状态 | 用户已掌握/未掌握的知识 | "用户已理解 Attention，但不熟悉 MoE" |

### 2.5 没有 Human-in-the-Loop

**现状：** `createNote` 直接创建，`markReviewed` 直接标记。如果 Agent 误解用户意图，没有纠错机制。

**应有的交互：**

```
Agent: "我准备创建一篇关于 Transformer 注意力机制的笔记，
       内容如下：[预览]
       确认创建吗？还是需要修改？"

用户: "内容可以，但标题改成 'Transformer 核心机制总结'"

Agent: "已按要求创建，ID: xxx"
```

### 2.6 子任务执行限制

**现状代码 (`AgentService:322-335`)：**

```java
// 最多 2 轮工具调用（子任务应该更轻量）
for (int i = 0; i < 2; i++) {
    // ... 工具调用
}
// 达到最大轮次，强制生成
```

**问题：**
- 子任务最多 2 轮工具调用，过于死板
- 子任务之间互不通信，无法协作
- 子任务失败后直接用错误信息兜底，不会重试

---

## 三、改进方案

### 3.1 第一层：Agent Loop — 打破固定流水线

**目标：** 让 LLM 自主决定每一步做什么，而不是走固定流程。

**核心改动：**

```java
/**
 * Agent 主循环：LLM 自主决策每一步
 */
public class AgentLoop {

    private static final int MAX_ITERATIONS = 10;

    public AgentResult run(String query, String userId, List<ToolSpecification> tools) {
        AgentState state = new AgentState(query);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt()));
        messages.add(UserMessage.from(query));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            // 1. LLM 思考下一步
            Response<AiMessage> response = llm.generate(messages, tools);
            AiMessage aiMessage = response.content();

            // 2. 判断 LLM 想做什么
            if (aiMessage.hasToolExecutionRequests()) {
                // 执行工具
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    String result = executeTool(req, userId);
                    messages.add(ToolExecutionResultMessage.from(req, result));

                    // 3. 反思：结果质量如何？
                    ToolEvaluation eval = evaluateResult(req, result, state);
                    if (eval.quality() == POOR) {
                        messages.add(SystemMessage.from(
                            "上次尝试的结果不够好：" + eval.reason() +
                            "\n建议换个策略：" + eval.suggestion()
                        ));
                    }
                }
            } else if (needsClarification(aiMessage)) {
                // 需要用户确认
                return AgentResult.needClarification(aiMessage.text());
            } else {
                // LLM 认为任务完成
                return AgentResult.done(aiMessage.text(), state.getTrace());
            }
        }

        return AgentResult.done(state.getBestAnswer(), state.getTrace());
    }
}
```

**关键设计点：**

1. **动态工具选择：** LLM 根据当前状态自主选择工具，不预设流程
2. **中间结果驱动：** 上一步的结果影响下一步的决策
3. **早停机制：** LLM 判断任务完成时可提前结束
4. **用户交互：** 需要时可暂停等待用户输入

### 3.2 第二层：反思与纠错机制

**目标：** 每步执行后评估结果质量，动态调整策略。

```java
/**
 * 工具执行结果评估器
 */
@Service
public class ToolResultEvaluator {

    private final ChatLanguageModel llm;

    /**
     * 评估工具执行结果的质量
     */
    public ToolEvaluation evaluate(ToolExecutionRequest request,
                                    String result,
                                    AgentState state) {
        String prompt = String.format("""
            评估以下工具调用结果的质量：

            工具：%s
            参数：%s
            原始目标：%s
            工具返回：%s

            判断：
            1. 结果是否充分回答了问题？
            2. 是否需要换个关键词/工具重试？
            3. 结果是否包含错误或矛盾？

            返回 JSON：
            {"quality": "GOOD|POOR", "reason": "原因", "suggestion": "改进建议"}
            """, request.name(), request.arguments(),
            state.getOriginalQuery(),
            result.length() > 1000 ? result.substring(0, 1000) + "..." : result);

        // 解析 LLM 返回的评估结果
        String evalJson = llm.generate(prompt);
        return parseEvaluation(evalJson);
    }
}

/**
 * 工具执行结果质量枚举
 */
public enum ResultQuality {
    GOOD,   // 结果足够好，继续
    POOR,   // 结果不理想，需要重试或换策略
    ERROR   // 执行失败
}
```

**反思循环示例：**

```
用户：帮我找一下关于 Attention 的笔记

第 1 轮：
  思考：需要搜索笔记
  行动：searchNotes("Attention")
  结果：找到 2 篇，但内容较浅
  评估：POOR — 结果不够详细，建议搜索更具体的关键词

第 2 轮：
  思考：换个关键词试试
  行动：searchNotes("Transformer 注意力机制 self-attention")
  结果：找到 5 篇详细笔记
  评估：GOOD — 结果充分

最终回答：基于 5 篇笔记生成回答
```

### 3.3 第三层：持久记忆系统

**目标：** 让 Agent 越用越好，记住用户的偏好和历史。

```java
/**
 * 用户记忆管理器
 */
@Service
public class UserMemoryService {

    private final MemoryRepository memoryRepository;
    private final ChatLanguageModel llm;

    /**
     * 从对话中提取值得记住的信息
     */
    public UserMemory extractFromConversation(String userId,
                                               List<ChatMessage> conversation) {
        String prompt = """
            从以下对话中提取关于用户的重要信息，用于未来个性化服务。

            对话内容：
            %s

            提取维度：
            1. 用户身份（职业、角色）
            2. 知识水平（熟悉什么、不熟悉什么）
            3. 偏好（回答风格、格式、详细程度）
            4. 当前关注点（在学什么、做什么项目）
            5. 常见需求（经常问什么类型的问题）

            返回 JSON 格式，只提取明确的信息，不要猜测。
            """.formatted(formatConversation(conversation));

        String result = llm.generate(prompt);
        return parseMemory(result);
    }

    /**
     * 加载用户记忆，注入到系统提示
     */
    public String buildMemoryContext(String userId) {
        UserMemory memory = memoryRepository.findByUserId(userId);
        if (memory == null) return "";

        return String.format("""
            ## 用户画像
            - 身份：%s
            - 知识水平：%s
            - 回答偏好：%s
            - 当前关注：%s
            - 历史任务：%s
            """,
            memory.getIdentity(),
            memory.getKnowledgeLevel(),
            memory.getStylePreference(),
            memory.getCurrentFocus(),
            memory.getRecentTasks()
        );
    }

    /**
     * 对话结束后更新记忆
     */
    public void updateMemory(String userId, List<ChatMessage> conversation) {
        UserMemory newInsights = extractFromConversation(userId, conversation);
        UserMemory existing = memoryRepository.findByUserId(userId);

        if (existing == null) {
            memoryRepository.save(userId, newInsights);
        } else {
            // 合并记忆，新的覆盖旧的
            existing.merge(newInsights);
            memoryRepository.save(userId, existing);
        }
    }
}
```

**记忆结构：**

```java
public class UserMemory {
    private String userId;

    // 用户画像
    private String identity;          // "后端开发，3年Java经验"
    private String knowledgeLevel;    // "熟悉Spring，不熟悉ML"
    private String stylePreference;   // "喜欢结构化回答，带代码示例"

    // 当前状态
    private String currentFocus;      // "正在学习Transformer架构"
    private List<String> recentTasks; // ["整理RAG笔记", "学习Attention机制"]

    // 知识图谱
    private Map<String, Double> topicMastery;  // "attention" → 0.8 (掌握度)
}
```

### 3.4 第四层：Human-in-the-Loop

**目标：** 关键操作让用户确认，避免误操作。

```java
/**
 * 人机交互管理器
 */
@Service
public class HumanInteractionService {

    /**
     * 判断操作是否需要用户确认
     */
    public boolean needsConfirmation(ToolExecutionRequest request) {
        return switch (request.name()) {
            case "createNote" -> true;   // 创建笔记需要确认
            case "editNote" -> true;     // 编辑笔记需要确认
            case "deleteNote" -> true;   // 删除笔记需要确认
            case "searchNotes" -> false; // 搜索不需要
            case "ragSummary" -> false;  // 检索不需要
            default -> false;
        };
    }

    /**
     * 生成确认请求
     */
    public ConfirmRequest buildConfirmRequest(ToolExecutionRequest request) {
        return switch (request.name()) {
            case "createNote" -> {
                Map<String, String> args = parseArgs(request.arguments());
                yield new ConfirmRequest(
                    "创建笔记确认",
                    "标题：" + args.get("title") + "\n" +
                    "内容预览：" + truncate(args.get("content"), 200) + "\n\n" +
                    "确认创建吗？",
                    List.of("确认创建", "修改后创建", "取消")
                );
            }
            case "editNote" -> {
                Map<String, String> args = parseArgs(request.arguments());
                yield new ConfirmRequest(
                    "编辑笔记确认",
                    "笔记 ID：" + args.get("noteId") + "\n" +
                    "修改内容：" + args.get("diff") + "\n\n" +
                    "确认修改吗？",
                    List.of("确认修改", "查看原文", "取消")
                );
            }
            default -> null;
        };
    }
}
```

**交互流程：**

```
用户：帮我创建一篇关于 RAG 的笔记

Agent：我准备创建以下笔记：
       ━━━━━━━━━━━━━━━━━━━━━━
       标题：RAG 技术总结
       内容：
       ## 什么是 RAG
       RAG (Retrieval-Augmented Generation) 是一种结合检索和生成的方法...

       ## 核心组件
       1. 文档解析
       2. 向量化
       3. 检索
       4. 生成
       ━━━━━━━━━━━━━━━━━━━━━━

       [确认创建] [修改后创建] [取消]

用户：标题改成 "RAG 从入门到实践"

Agent：已创建笔记，ID: xxx，标题: "RAG 从入门到实践"
```

### 3.5 工具扩展方案

**短期可加的工具（1-2 周）：**

```java
@Tool("编辑已有笔记，noteId 为笔记ID，diff 为修改内容")
public String editNote(
    @P("笔记ID") String noteId,
    @P("修改内容，JSON格式：{\"operation\": \"append|replace|delete\", \"content\": \"...\"}") String diff,
    @ToolMemoryId String userId) {
    // 实现笔记编辑
}

@Tool("合并多篇笔记为一篇新笔记")
public String mergeNotes(
    @P("笔记ID列表，逗号分隔") String noteIds,
    @P("新笔记标题") String newTitle,
    @ToolMemoryId String userId) {
    // 实现笔记合并
}

@Tool("搜索网页获取最新信息")
public String searchWeb(@P("搜索关键词") String query) {
    // 调用搜索 API
}
```

**中期可加的工具（1-2 月）：**

```java
@Tool("执行 Python 代码并返回结果")
public String executeCode(
    @P("Python 代码") String code,
    @P("是否需要安装依赖") boolean installDeps) {
    // 在沙箱中执行代码
}

@Tool("生成 Mermaid 图表")
public String generateDiagram(
    @P("图表类型：flowchart|sequence|class|er") String type,
    @P("图表描述") String description) {
    // 生成图表代码
}

@Tool("调用外部 API")
public String callExternalAPI(
    @P("API URL") String url,
    @P("请求参数 JSON") String params,
    @P("请求方法 GET|POST") String method) {
    // 调用外部 API
}
```

---

## 四、实施路线图

### Phase 1：Agent Loop（2 周）

- [ ] 实现 `AgentLoop` 主循环，替代固定流水线
- [ ] 实现 `AgentState` 状态管理
- [ ] 支持动态工具选择
- [ ] 支持早停机制

### Phase 2：反思机制（1 周）

- [ ] 实现 `ToolResultEvaluator` 结果评估
- [ ] 在 Agent Loop 中集成反思逻辑
- [ ] 支持工具失败重试和策略切换

### Phase 3：记忆系统（2 周）

- [ ] 设计 `UserMemory` 数据模型
- [ ] 实现 `UserMemoryService` 记忆管理
- [ ] 实现记忆提取和更新逻辑
- [ ] 集成到系统提示中

### Phase 4：Human-in-the-Loop（1 周）

- [ ] 实现 `HumanInteractionService`
- [ ] 定义需要确认的操作类型
- [ ] 实现确认请求和响应处理
- [ ] 前端适配确认交互 UI

### Phase 5：工具扩展（持续）

- [ ] `editNote` — 笔记编辑
- [ ] `mergeNotes` — 笔记合并
- [ ] `searchWeb` — 联网搜索
- [ ] `executeCode` — 代码执行（可选）
- [ ] `generateDiagram` — 图表生成（可选）

---

## 五、预期效果

### 改进前 vs 改进后对比

| 维度 | 改进前 | 改进后 |
|------|--------|--------|
| **决策方式** | 固定流水线，LLM 只选工具 | LLM 自主决定每一步 |
| **工具能力** | 6 个工具，5 个读操作 | 10+ 工具，能读能写能执行 |
| **错误处理** | 工具失败直接兜底 | 反思后重试或换策略 |
| **记忆** | 会话内滑动窗口 | 用户画像 + 长期记忆 |
| **人机交互** | 全自动无确认 | 关键操作用户确认 |
| **子任务** | 并行独立，互不通信 | 结果可驱动后续任务 |

### 典型场景改进

**场景：用户说 "帮我整理一下机器学习的笔记"**

**改进前：**
```
Supervisor 拆分 → 搜索笔记 → 检索知识库 → 合成回答
结果：一份泛泛的总结，可能遗漏用户真正关心的内容
```

**改进后：**
```
Agent 思考：用户要整理笔记，先看看有哪些笔记
→ 搜索笔记 → 发现 8 篇 ML 笔记
→ 思考：笔记覆盖了监督学习和无监督学习，但没有强化学习
→ 搜索知识库强化学习资料 → 找到 3 篇
→ 思考：可以创建一篇强化学习笔记来补全
→ 创建笔记（需用户确认）
→ 思考：现在可以生成完整的学习路线图
→ 生成回答，包含：已掌握/未掌握/推荐学习顺序
```

---

## 六、总结

项目的 Agent 架构已经搭好了骨架（Supervisor + Tools + Pipeline），具备了任务规划、工具调用、质量审查等基础能力。

缺的是"灵魂"——让 Agent 真正自主思考、反思、记忆的能力。这些不是推倒重来，而是在现有架构上逐步叠加：

1. **Agent Loop** — 让 Agent 自主决策，打破固定流水线
2. **反思机制** — 每步执行后评估，动态调整策略
3. **持久记忆** — 记住用户偏好，越用越好
4. **人机协作** — 关键操作让用户确认
5. **工具扩展** — 从"只能读"到"能读能写能执行"

按 Phase 1-5 的路线图逐步实施，可以在 6-8 周内将现有系统升级为真正的 Agent。

---

## 七、工具调用准确率优化方案

### 7.1 问题诊断

当前系统使用 GLM-4-Flash 模型（`ZHIPU` 类型），该模型的 function calling 能力相对较弱，导致工具调用准确率低。具体表现：

| 现象 | 原因 | 频率 |
|------|------|------|
| 应调工具但直接文本回复 | 模型未理解何时该调工具 | 高 |
| 调错工具（如该用 searchNotes 用了 ragSummary） | 工具描述不够区分 | 中 |
| 工具参数填错 | 参数描述不够具体 | 中 |
| 重复调用同一工具 | 缺乏状态感知 | 低 |

### 7.2 已实施的优化

#### 7.2.1 工具描述加触发场景

每个 `@Tool` 注解增加了具体的触发场景和示例，让 LLM 更容易判断何时用哪个工具：

```java
// 优化前
@Tool("搜索用户自己的笔记，适用于用户要查找、搜索自己的笔记")

// 优化后
@Tool("搜索用户自己的笔记。触发场景：用户提到'笔记'、'搜索'、'查找'、'找找'、'我的笔记'、'记录'等关键词时必须调用此工具。例如：'帮我找一下线程池相关的笔记'、'我之前记过什么'、'搜索笔记'")
```

**效果：** 工具描述从"做什么"扩展为"什么时候做"，降低了 LLM 的推断难度。

#### 7.2.2 系统提示词加 Few-shot 示例

在 `main_prompt.txt` 中加入 13 个工具调用示例，覆盖所有工具的典型使用场景：

```
### 示例1：搜索笔记
用户：帮我找一下线程池相关的笔记
→ 调用 searchNotes(query="线程池")
→ 根据返回结果回答用户
```

**效果：** Few-shot 示例比规则描述更直观，小模型通过模仿示例学习工具调用。

#### 7.2.3 Agent Loop + 反思机制

实现 `AgentLoop` 替代固定流水线，集成 `ToolResultEvaluator` 反思机制：

```
思考 → 行动 → 观察结果 → 评估质量 → 决定下一步
                    ↑                    ↓
                    ←── 结果不好，换个策略 ←┘
```

**效果：** 当工具返回"未找到"时，自动注入反思提示引导 LLM 换关键词重试。

### 7.3 进阶优化方案（待实施）

#### 7.3.1 模型升级（效果最显著）

**问题：** GLM-4-Flash 是轻量模型，function calling 能力有限。

**方案：** 工具选择和参数生成使用更强的模型，回答生成使用当前模型。

```java
// ModelFactory.java 新增
public ChatLanguageModel createToolCallingModel() {
    String type = props.getLlm().getType();
    if ("ZHIPU".equalsIgnoreCase(type)) {
        return OpenAiChatModel.builder()
                .apiKey(props.getLlm().getZhipu().getApiKey())
                .baseUrl(props.getLlm().getZhipu().getBaseUrl())
                .modelName("glm-4")  // 使用完整版 GLM-4
                .temperature(0.05)    // 更低温度，更精确
                .build();
    }
    return createPreciseModel();
}
```

**成本：** GLM-4 价格约为 GLM-4-Flash 的 10 倍，但只在工具选择阶段使用（1-2 轮），总成本可控。

**预期效果：** 工具调用准确率从 ~60% 提升到 ~90%。

#### 7.3.2 意图预分类器（减少误判）

**问题：** LLM 需要同时判断"是否需要工具"和"用哪个工具"，任务太重。

**方案：** 用轻量规则引擎预分类意图，再让 LLM 选择具体工具。

```java
@Service
public class IntentClassifier {

    // 关键词 → 工具映射（规则引擎，零延迟）
    private static final Map<String, List<String>> KEYWORD_TOOLS = Map.of(
        "笔记",     List.of("searchNotes"),
        "搜索",     List.of("searchNotes"),
        "查找",     List.of("searchNotes"),
        "知识库",   List.of("ragSummary"),
        "文档",     List.of("ragSummary"),
        "复习",     List.of("getTodayReviews"),
        "统计",     List.of("getNoteStats"),
        "创建",     List.of("createNote"),
        "编辑",     List.of("editNote"),
        "追加",     List.of("appendNote"),
        "删除",     List.of("deleteNote"),
        "合并",     List.of("mergeNotes"),
        "链接",     List.of("fetchUrl"),
        "图表",     List.of("generateDiagram")
    );

    /**
     * 基于关键词预判可能需要的工具
     * 返回 null 表示无法判断，交给 LLM
     */
    public List<String> predictTools(String query) {
        List<String> candidates = new ArrayList<>();
        for (var entry : KEYWORD_TOOLS.entrySet()) {
            if (query.contains(entry.getKey())) {
                candidates.addAll(entry.getValue());
            }
        }
        return candidates.isEmpty() ? null : candidates.stream().distinct().toList();
    }
}
```

**集成方式：** 在 AgentLoop 中，先用规则引擎预判，将结果注入系统提示：

```java
// AgentLoop.java
List<String> predictedTools = intentClassifier.predictTools(userQuery);
if (predictedTools != null) {
    systemPrompt += "\n\n[参考] 根据用户问题，可能需要的工具：" + String.join(", ", predictedTools);
}
```

**效果：** 减少 LLM 的判断负担，提高工具选择准确率。

#### 7.3.3 工具调用校验器（防止调错）

**问题：** LLM 可能调用不存在的工具或传错参数。

**方案：** 在执行工具前校验合法性。

```java
@Service
public class ToolCallValidator {

    /**
     * 校验工具调用是否合法
     */
    public ValidationResult validate(ToolExecutionRequest request,
                                     List<ToolSpecification> availableTools) {
        String toolName = request.name();

        // 1. 检查工具是否存在
        boolean exists = availableTools.stream()
                .anyMatch(t -> t.name().equals(toolName));
        if (!exists) {
            return ValidationResult.invalid("工具不存在: " + toolName);
        }

        // 2. 检查必填参数
        Map<String, String> args = parseArguments(request.arguments());
        List<String> missingParams = checkRequiredParams(toolName, args);
        if (!missingParams.isEmpty()) {
            return ValidationResult.invalid("缺少必填参数: " + String.join(", ", missingParams));
        }

        // 3. 检查参数格式
        String formatError = checkParamFormat(toolName, args);
        if (formatError != null) {
            return ValidationResult.invalid(formatError);
        }

        return ValidationResult.valid();
    }

    private List<String> checkRequiredParams(String toolName, Map<String, String> args) {
        return switch (toolName) {
            case "searchNotes" -> args.containsKey("query") ? List.of() : List.of("query");
            case "createNote" -> {
                if (!args.containsKey("title")) yield List.of("title");
                if (!args.containsKey("content")) yield List.of("content");
                yield List.of();
            }
            case "editNote" -> args.containsKey("noteId") ? List.of() : List.of("noteId");
            case "fetchUrl" -> args.containsKey("url") ? List.of() : List.of("url");
            default -> List.of();
        };
    }
}
```

**效果：** 防止无效工具调用浪费 token 和时间。

#### 7.3.4 上下文压缩优化（减少干扰）

**问题：** 历史消息中的关键词会干扰 LLM 的工具选择。

**方案：** 在工具调用前，压缩历史消息为摘要，只保留最近 3 轮原文。

```java
// ContextManager.java 新增
public List<ChatMessage> buildCompactMessages(List<ChatMessage> history, int recentCount) {
    if (history.size() <= recentCount * 2) {
        return history;  // 消息不多，直接返回
    }

    // 早期消息压缩为摘要
    List<ChatMessage> early = history.subList(0, history.size() - recentCount * 2);
    List<ChatMessage> recent = history.subList(history.size() - recentCount * 2, history.size());

    String summary = summarize(early);
    List<ChatMessage> result = new ArrayList<>();
    result.add(new ChatMessage("system", "[历史摘要] " + summary));
    result.addAll(recent);
    return result;
}
```

**效果：** 减少历史消息对工具选择的干扰，同时节省 token。

#### 7.3.5 工具调用日志分析（持续优化）

**方案：** 记录每次工具调用的详细日志，用于分析和优化。

```java
// 新增 ToolCallLog 实体
@Entity
@Table(name = "tool_call_logs")
public class ToolCallLog {
    @Id
    private String id;
    private String userId;
    private String sessionId;
    private String userQuery;        // 用户原始问题
    private String predictedTool;    // 规则引擎预测的工具
    private String actualTool;       // LLM 实际选择的工具
    private String toolArgs;         // 工具参数
    private String toolResult;       // 工具返回结果
    private ResultQuality quality;   // 结果质量评估
    private boolean correct;         // 工具选择是否正确（人工标注）
    private LocalDateTime createdAt;
}
```

**分析维度：**

| 指标 | 计算方式 | 目标 |
|------|---------|------|
| 工具调用率 | 应调工具的查询中实际调了的比例 | >95% |
| 工具选择准确率 | 调对工具的比例 | >85% |
| 参数正确率 | 参数格式正确的比例 | >90% |
| 反思成功率 | 反思后重试成功的比例 | >70% |

**优化流程：**

```
收集日志 → 分析失败案例 → 优化工具描述/示例 → 验证效果 → 迭代
```

### 7.4 优化优先级

| 优化项 | 难度 | 效果 | 优先级 |
|--------|------|------|--------|
| 工具描述加触发场景 | 低 | 高 | ✅ 已完成 |
| Few-shot 示例 | 低 | 高 | ✅ 已完成 |
| Agent Loop + 反思 | 中 | 高 | ✅ 已完成 |
| 模型升级（GLM-4） | 低 | 很高 | P0 - 立即 |
| 意图预分类器 | 中 | 中 | P1 - 1周内 |
| 工具调用校验器 | 低 | 中 | P1 - 1周内 |
| 上下文压缩优化 | 中 | 中 | P2 - 2周内 |
| 工具调用日志分析 | 高 | 长期 | P2 - 2周内 |

### 7.5 预期效果

| 阶段 | 工具调用准确率 | 主要优化手段 |
|------|--------------|-------------|
| 优化前 | ~50-60% | 基础系统提示词 |
| 第一阶段（当前） | ~70-75% | 工具描述 + Few-shot + 反思 |
| 第二阶段 | ~85-90% | 模型升级 + 意图预分类 |
| 第三阶段 | ~90-95% | 校验器 + 日志分析迭代 |
