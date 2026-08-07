# agent修改方案

## 1. 目标与定位

### 1.1 当前项目真实定位
结合当前代码实现，这个项目里的 Agent 能力更准确地说是：

- **基于 LLM 规划的 Supervisor-Worker Agent 化工作流**
- 已具备一定的目标驱动和工具调用循环能力
- 但整体仍然受限于一次请求内的同步/准同步流程，不具备真正意义上的长期自治、跨请求持久运行、深层自主反思与动态重规划能力

当前关键代码位置：

- `backend-java/src/main/java/com/rag/notebook/agent/AgentService.java`
- `backend-java/src/main/java/com/rag/notebook/agent/AgentLoop.java`
- `backend-java/src/main/java/com/rag/notebook/agent/SupervisorService.java`
- `backend-java/src/main/java/com/rag/notebook/agent/ContextManager.java`
- `backend-java/src/main/java/com/rag/notebook/agent/ConversationContextManager.java`

### 1.2 本次改造目标
本次方案目标不是一步到位做“完全开放式通用 Agent”，而是结合当前项目实际情况，分阶段升级到：

> **受限自治的、可持久化任务状态的 Supervisor-Worker Agent Runtime**

达到以下能力：

1. 支持任务状态持久化，不再完全绑定单次 SSE 请求生命周期。
2. Worker 从“单次执行”升级为“有限闭环执行”。
3. 支持基于执行结果的反思和动态重规划。
4. 支持跨轮次、跨请求的工作记忆与任务进度恢复。
5. 保留当前项目已有的缓存、降级、RAG、评估、上下文压缩等工程能力。
6. 为后续再向“更开放式 Agent”演进打下架构基础。

---

## 2. 当前实现分析

## 2.1 已有能力

### 2.1.1 Supervisor 分诊与结构化拆分
当前 `SupervisorService` 已实现：

- 对问题复杂度做判断
- 将复杂问题拆分为 2-4 个子任务
- 输出 `goal`、`successCriteria`、`dependsOn`、`executionMode`
- JSON 清洗、括号提取、失败回退、Redis 缓存

这是一个很好的“任务入口层”。

### 2.1.2 AgentLoop 已具备初步闭环
当前 `AgentLoop` 已经不是纯静态工具流，而是具备以下雏形：

- 有 `MAX_ITERATIONS = 10`
- 具备 `Goal -> Tool Call -> Observation -> State Update -> Goal Check` 的循环
- 有 `AgentState`
- 有 `GoalEvaluator`
- 有 `ToolResultEvaluator`
- 能拦截重复调用、识别无进展状态、注入状态视图
- 支持工具执行后的目标达成判断

这说明项目已经具备从“工作流”向“受限自治 Agent”升级的基础。

### 2.1.3 已有工程化配套能力
当前还具备很多对 Agent 非常关键的基础设施：

- `ContextManager`：上下文压缩、工具结果压缩、摘要压缩
- `ConversationContextManager`：会话级上下文引用解析
- `QualityReviewer`：对答案进行审查
- `ResponseComposer` / `WriterService`：做多子任务结果整合
- `HybridRetriever`：成熟的混合检索与降级能力
- `EvaluationService` / `AblationExperimentService`：评估和实验框架

这些能力不需要推翻，可以复用到新的 Agent Runtime 中。

## 2.2 当前主要限制

### 2.2.1 生命周期仍绑定单次请求
当前 `AgentService.streamAgentResponse()` 的主流程是：

- 收到请求
- 构造历史消息和附件上下文
- 调 Supervisor
- 调 AgentLoop
- 写会话消息
- SSE 返回

也就是说：

- 任务状态未持久化
- 服务重启后无法恢复未完成任务
- 用户关闭页面后任务无法持续执行
- 无法真正支持长任务、延迟任务、异步恢复任务

### 2.2.2 反思与重规划仍偏“轮内提示”，不是显式 Runtime 能力
当前 `AgentLoop` 的反思能力主要通过：

- `needsReflection()`
- `buildStateViewForReflection()`
- `GoalEvaluator`
- `ToolResultEvaluator`

来实现。

但目前它仍然存在这些问题：

1. 反思结果没有独立持久化。
2. 重规划没有形成独立服务层。
3. 任务图不能动态新增、删除、重排。
4. 失败原因没有形成结构化事件流。

### 2.2.3 记忆仍以会话内短期状态为主
当前记忆更接近：

- 上下文压缩
- 会话级指代记忆
- `AgentState` 的运行期事实积累

但还没有：

- 长任务工作记忆持久化
- 历史任务经历的情景记忆
- 用户偏好和稳定事实的语义记忆
- 可复用任务套路的程序性记忆

### 2.2.4 工具系统仍偏固定注册
当前工具通过 `AgentTools` + `@Tool` 自动提取并在 `AgentLoop.executeTool()` 中用 `switch` 分发。

优点是简单可控。
缺点是：

- 工具扩展耦合到代码
- 工具元信息不统一
- 风险级别、超时、审批策略未抽象为独立模型
- 不利于后续动态工具发现和更开放的 Agent 决策

---

## 3. 改造原则

本次改造遵循以下原则：

1. **先增强 Runtime，不推翻现有能力。**
2. **先做受限自治，再谈开放式通用 Agent。**
3. **优先做可持久化、可恢复、可观测、可停止。**
4. **保留现有 RAG、评估、缓存、降级链路。**
5. **高风险写操作必须保留人工确认或策略控制能力。**
6. **不要把“反思”只做成 prompt，要做成结构化状态与服务。**

---

## 4. 总体改造架构

建议新增一层 Agent Runtime，形成如下结构：

```text
ChatController / ChatService / SSE
        |
        v
AgentTaskService
        |
        v
AgentRuntime
   ├── PlannerService
   ├── WorkerLoopService
   ├── ReflectionService
   ├── ReplanningService
   ├── MemoryService
   ├── ToolRegistry / ToolExecutionService
   ├── AgentPolicyService
   └── AgentEventService
```

与现有模块的关系：

- `SupervisorService` 复用为 `PlannerService` 的初始规划器
- `AgentLoop` 重构为 `WorkerLoopService`
- `GoalEvaluator`、`ToolResultEvaluator` 继续复用
- `ConversationContextManager` 继续用于轻量会话引用解析
- `ContextManager` 继续用于上下文预算控制
- `ResponseComposer`、`WriterService` 复用为最终结果整合器

---

## 5. 分阶段实施方案

## 5.1 第一阶段：从请求内循环升级为“可持久化任务”

### 5.1.1 目标
让 Agent 任务不再只是 `streamAgentResponse()` 内部的一次执行，而是成为可持久化、可恢复、可查询状态的任务。
本轮回答背后的 agent 执行状态机持久化

### 5.1.2 新增数据模型
建议新增以下实体：

#### `AgentTask`

```java
class AgentTask {
    String taskId;
    String userId;
    String sessionId;
    String originalQuery;
    String normalizedGoal;
    String status;
    String executionMode;
    Integer iterationCount;
    Integer toolCallCount;
    Integer tokenConsumed;
    String currentPlanSnapshot;
    String currentSummary;
    String finalAnswer;
    String failureReason;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;
    LocalDateTime nextRunAt;
}
```

#### `AgentTaskStep`

记录子任务和计划节点：

```java
class AgentTaskStep {
    String id;
    String taskId;
    String stepId;
    String label;
    String goal;
    String status;
    Integer sequenceNo;
    String dependsOnJson;
    String successCriteriaJson;
    String resultSummary;
}
```

#### `AgentTaskEvent`

记录执行事件流：

```java
class AgentTaskEvent {
    String id;
    String taskId;
    String eventType;
    String payloadJson;
    LocalDateTime createdAt;
}
```

事件类型建议包括：

- `TASK_CREATED`
- `PLAN_CREATED`
- `STEP_STARTED`
- `TOOL_CALLED`
- `TOOL_SUCCEEDED`
- `TOOL_FAILED`
- `REFLECTION_CREATED`
- `REPLAN_CREATED`
- `TASK_BLOCKED`
- `TASK_COMPLETED`
- `TASK_FAILED`

### 5.1.3 状态机设计
建议将任务状态统一为：

- `CREATED`
- `PLANNING`
- `RUNNING`
- `WAITING_USER`
- `REFLECTING`
- `REPLANNING`
- `BLOCKED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

### 5.1.4 新增服务

#### `AgentTaskService`
职责：

- 创建任务
- 查询任务
- 更新任务状态
- 持久化最终结果
- 绑定会话与 taskId

#### `AgentEventService`
职责：

- 写入事件表
- 推送 SSE 事件
- 提供前端任务追踪所需的数据

### 5.1.5 对 `AgentService` 的改造
当前 `streamAgentResponse()` 过重，建议拆分：

#### 当前问题
`AgentService` 同时负责：

- 会话写入
- SSE
- Supervisor 分诊
- 单 Agent / 顺序 / 并行管道
- 结果整合
- trace 保存

#### 目标改造
保留 `AgentService` 作为 API 入口，但只负责：

1. 解析请求参数
2. 创建 `AgentTask`
3. 发起 `AgentRuntime.start(taskId)`
4. 建立 SSE 订阅

把执行主逻辑下沉到 `AgentRuntime`。

---

## 5.2 第二阶段：将 `AgentLoop` 提升为真正的 Worker Runtime

## 5.2.1 当前 `AgentLoop` 的优点
当前已经有：

- 最大迭代轮数
- 重复调用拦截
- Tool Observation 注入
- GoalEvaluator
- 无进展状态处理
- 工具结果质量评估

这是非常好的基础，不建议重写。

## 5.2.2 需要做的改造

### 新增 `WorkerLoopService`
从 `AgentLoop` 中提炼出运行上下文：

```java
class WorkerLoopContext {
    String taskId;
    String stepId;
    AgentState agentState;
    List<ChatMessage> messages;
    Integer maxIterations;
    Integer maxToolCalls;
    Integer maxTokens;
}
```

### `AgentLoop.run()` 需要扩展的能力
当前签名：

```java
run(systemPrompt, userQuery, historyMessages, userId, sessionId, activeTools, emitter, goal, successCriteria)
```

建议升级为基于 `WorkerLoopContext`：

```java
WorkerLoopResult run(WorkerLoopContext context)
```

这样更容易支持：

- 恢复执行
- 暂停后继续
- 任务级预算限制
- 更清晰的状态落库

### 每轮执行后持久化状态
每轮至少落库以下内容：

- `iterationCount`
- 当前 `AgentState` 摘要
- 最新观察结果
- 最新反思结果
- 最新失败原因
- 最新候选策略

### 扩展终止条件
当前只有 `MAX_ITERATIONS`。

建议增加：

- 最大工具调用次数
- 最大 Token 消耗
- 最大连续无进展次数
- 最大重复动作次数
- 高风险操作审批未通过时中止
- 任务超过最大运行时长自动挂起

---

## 5.3 第三阶段：显式引入 Reflection 与 Replanning

## 5.3.1 当前不足
当前反思更多是通过 message 注入给 LLM，结构化程度不够。

### 目标
将反思和重规划做成独立服务，而不是藏在 prompt 中。

## 5.3.2 新增 `ReflectionService`
输入：

- 当前目标
- 当前步骤
- 工具调用历史
- 工具执行结果
- 当前工作记忆
- 失败记录
- 当前候选答案/证据

输出建议结构化：

```java
class ReflectionResult {
    boolean goalAchieved;
    boolean shouldReplan;
    boolean shouldAskUser;
    String failureType;
    String rootCause;
    List<String> missingEvidence;
    List<String> recommendedActions;
    String summary;
    Double confidence;
}
```

### 反思触发时机
建议在以下场景触发：

1. 连续 2 轮无进展
2. 同类工具连续失败
3. 工具执行成功但证据不足
4. 子任务执行完但无法满足 successCriteria
5. 达到预算阈值前做一次是否继续的判断

## 5.3.3 新增 `ReplanningService`
职责：

- 接收 `ReflectionResult`
- 基于当前步骤和任务上下文做计划修改
- 新增、删除、重排、拆分、合并步骤

建议输出：

```java
class ReplanResult {
    String replanReason;
    List<AgentTaskStep> newSteps;
    boolean replaceRemainingPlan;
    String summary;
}
```

### 与当前 `SupervisorService` 的关系
- `SupervisorService`：做初始规划
- `ReplanningService`：做执行中动态调整

可以先复用 `SupervisorService` 的 prompt 风格，但输入必须包含执行历史、失败原因和当前任务状态。

---

## 5.4 第四阶段：补齐多层记忆体系

## 5.4.1 当前可复用部分
### `ContextManager`
继续负责：

- 历史消息压缩
- 工具结果压缩
- token 预算管理

### `ConversationContextManager`
继续负责：

- 会话内活动笔记引用解析

## 5.4.2 新增 `MemoryService`
建议将记忆拆成四层：

### 1. 工作记忆（Working Memory）
当前任务内动态变化的信息。

存储内容：

- 当前目标
- 已知事实
- 已完成步骤
- 最近工具结果
- 当前候选策略

可直接复用 `AgentState`，但要持久化到数据库或 Redis。

### 2. 情景记忆（Episodic Memory）
记录历史任务执行经历。

示例：

- 某类问题曾经通过什么路径完成
- 某个工具在什么场景下经常失败
- 用户曾否定过哪些回答模式

新增表建议：`agent_episode_memory`

### 3. 语义记忆（Semantic Memory）
沉淀稳定事实。

示例：

- 用户偏好中文回答
- 用户习惯将导图写回原笔记
- 某个知识库下常见内容主题

新增表建议：`agent_semantic_memory`

### 4. 程序性记忆（Procedural Memory）
沉淀任务套路、可复用技能。

这一层建议与当前 `skill` 模块结合：

- 将高频完成路径沉淀为 Skill
- 让 Agent 在规划时优先选择已有 Skill
- 逐步形成“任务模板库”

---

## 5.5 第五阶段：工具系统标准化

## 5.5.1 当前问题
`AgentLoop.executeTool()` 中用 `switch` 调 `AgentTools`，对于当前项目是够用的，但如果要升级到更强 Agent，需要标准化工具元数据。

## 5.5.2 新增 `ToolRegistry`
建议抽象：

```java
class ToolDefinition {
    String name;
    String description;
    String inputSchema;
    String outputSchema;
    String category;
    String riskLevel;
    Integer timeoutSeconds;
    boolean requiresApproval;
    boolean idempotent;
}
```

### 工具类别建议
- `READ`
- `WRITE`
- `SEARCH`
- `GENERATE`
- `EXTERNAL`
- `SYSTEM`

### 风险等级建议
- `LOW`
- `MEDIUM`
- `HIGH`

### 当前工具可先分类
例如：

- `listNotes` / `getNote` / `searchNotes` -> `READ`
- `createNote` / `editNote` / `appendNote` / `deleteNote` -> `WRITE`
- `ragSummary` -> `SEARCH`
- `generateDiagram` / `generateMindMap` -> `GENERATE`
- `fetchUrl` -> `EXTERNAL`

## 5.5.3 新增 `ToolExecutionService`
职责：

- 统一执行工具
- 封装超时
- 记录耗时
- 标准化返回结构
- 产生日志和事件

建议统一返回：

```java
class ToolExecutionResult {
    boolean success;
    String toolName;
    String errorCode;
    boolean retryable;
    Map<String, Object> data;
    List<String> evidence;
    String summary;
    long latencyMs;
}
```

当前 `ToolResult` 可作为基础逐步扩展。

---

## 5.6 第六阶段：策略与安全控制

如果 Agent 变得更自治，必须增强边界控制。

## 5.6.1 新增 `AgentPolicyService`
职责：

- 控制预算
- 控制最大轮数
- 控制高风险工具审批
- 控制工具调用频率
- 控制是否允许访问外网

### 规则建议

1. 单任务最大 10 轮，可配置。
2. 单任务最大 20 次工具调用。
3. 单任务最大 Token 消耗阈值。
4. `deleteNote`、`mergeNotes`、批量编辑类操作需要审批。
5. `fetchUrl` 限制域名白名单或默认禁用外网。
6. 连续 3 次相同失败原因直接 `BLOCKED`。
7. 同一工具同参连续 2 次调用自动拦截。

这些规则有一部分当前 `AgentLoop` 已经实现，建议统一收口到策略层。

---

## 6. 具体代码改造建议

## 6.1 `AgentService` 改造建议

### 当前职责过重
建议拆分为：

- `AgentApiFacade`：处理入口请求和 SSE
- `AgentTaskService`：任务创建与状态持久化
- `AgentRuntime`：真正执行逻辑

### 改造步骤
1. 保留 `streamAgentResponse()` 作为兼容入口。
2. 新增 taskId 创建逻辑。
3. 将当前顺序/并行执行逻辑下沉到 `AgentRuntime`。
4. SSE 只订阅事件，不直接承载全部业务逻辑。

## 6.2 `AgentLoop` 改造建议

### 建议保留并增强
`AgentLoop` 已经有很好的基础，不建议推翻。

重点增强：

1. 支持 `WorkerLoopContext` 输入。
2. 每轮状态持久化。
3. 将反思结果结构化输出。
4. 将重规划结果反馈给任务图。
5. 将预算与审批判断外移到 `AgentPolicyService`。

## 6.3 `SupervisorService` 改造建议

### 保留现有初始规划职责
当前 `SupervisorService` 很适合作为：

- 初始复杂度判断器
- 初始子任务规划器

### 需要新增的能力
1. 支持输入执行历史，作为重规划器复用。
2. 支持输出计划版本号。
3. 支持识别“需要用户澄清”的阻塞场景。
4. 支持把子任务显式分类为：
   - `READ`
   - `WRITE`
   - `GENERATE`
   - `VERIFY`

## 6.4 `AgentState` 改造建议

建议把当前 `AgentState` 进一步结构化，至少增加：

- `taskId`
- `stepId`
- `iteration`
- `candidateStrategies`
- `lastReflectionSummary`
- `lastFailureType`
- `blockedReason`
- `approvalRequired`

并增加序列化能力，用于任务恢复。

---

## 7. 数据库与表设计建议

建议新增以下表：

### 7.1 `agent_tasks`
记录主任务。

### 7.2 `agent_task_steps`
记录任务步骤与计划图。

### 7.3 `agent_task_events`
记录事件流，支撑回放和调试。

### 7.4 `agent_reflections`
记录反思结果。

### 7.5 `agent_memories`
统一存情景/语义/程序性记忆，可通过 `memory_type` 区分。

### 7.6 `agent_tool_metrics`
记录工具成功率、平均耗时、失败原因分布，方便后续做策略优化。

---

## 8. 前端与交互改造建议

当前前端主要通过 SSE 接收实时回复。

如果要支持长期任务，前端建议增加：

1. 任务列表页
2. 任务状态展示页
3. 执行事件时间线
4. 阻塞任务恢复入口
5. 高风险操作审批弹窗
6. 用户澄清输入入口

SSE 事件建议标准化为：

- `task_created`
- `planning`
- `step_started`
- `tool_call`
- `tool_result`
- `reflection`
- `replanning`
- `waiting_user`
- `approval_required`
- `completed`
- `failed`

---

## 9. 与现有 RAG / 评估模块的结合方式

## 9.1 RAG 模块继续复用
当前 `HybridRetriever`、`ragSummary`、降级逻辑不需要重做。

只需要：

- 在 Agent Runtime 中把检索行为视为工具调用
- 把检索结果纳入 Observation 和 Reflection
- 将检索耗时、Token、失败率纳入任务状态

## 9.2 评估模块可扩展为 Agent 评估
当前 `EvaluationService` 更偏问答质量评估。

后续可扩展出 `AgentExecutionEvaluationService`，评估：

- 任务完成率
- 平均轮数
- 平均工具调用次数
- 回退率
- 重规划率
- 被阻塞率
- Token 成本
- 最终回答质量

这能让 Agent 改造也能走实验化迭代，而不是只靠主观体验。

---

## 10. 风险与注意事项

## 10.1 不建议一步做到“开放式通用 Agent”
原因：

1. 当前业务域仍然集中在笔记和知识问答。
2. 工具集相对有限，不需要过度通用化。
3. 过早做全开放自治会显著增加失控、测试和成本问题。

建议先做到：

> 可持久化任务 + 有限自治循环 + 结构化反思 + 动态重规划

## 10.2 需要特别关注的风险

1. **循环失控**：必须有硬性预算。
2. **写操作风险**：需要审批策略。
3. **状态膨胀**：任务历史、事件、记忆增长要控量。
4. **记忆污染**：不是所有历史都该写入长期记忆。
5. **评估复杂度上升**：Agent 评估比 RAG 问答评估更难。

---

## 11. 推荐实施顺序

### Phase 1：任务持久化与 Runtime 抽离

目标：
- 新增 `agent_tasks` / `agent_task_events`
- 新增 `AgentTaskService` / `AgentRuntime`
- `AgentService` 只做入口与 SSE

收益：
- 任务可恢复、可追踪、可脱离单请求运行

### Phase 2：增强 `AgentLoop` 为 Worker Runtime

目标：
- 引入 `WorkerLoopContext`
- 每轮状态落库
- 统一预算控制

收益：
- 从请求内循环变成可管理的受限自治循环

### Phase 3：结构化反思与动态重规划

目标：
- 新增 `ReflectionService`
- 新增 `ReplanningService`
- 计划图可动态修改

收益：
- 具备更真实的 Agent 闭环能力

### Phase 4：多层记忆与工具标准化

目标：
- 新增 `MemoryService`
- 新增 `ToolRegistry` / `ToolExecutionService`
- 增加审批与策略层

收益：
- 为长期自治和更开放的能力铺路

### Phase 5：Agent 评估与实验体系

目标：
- 增加 Agent 运行指标
- 建立任务完成率/重规划率/成本评估

收益：
- 让 Agent 演进具备数据闭环

---

## 12. 最终建议结论

结合当前项目实际，最合理的演进路线不是直接追求“开放式通用 Agent”，而是：

1. **保留现有 Supervisor + AgentLoop 基础。**
2. **优先补任务持久化和 Runtime 抽象。**
3. **把反思、重规划、预算控制做成显式服务。**
4. **逐步补工作记忆、情景记忆和工具标准化。**
5. **最后再做 Agent 级评估和实验。**

如果上述前三阶段完成，这个项目就可以比较稳妥地升级为：

> **受限自治的、可持久化任务状态的 Supervisor-Worker Agent 系统**

而不是仅仅停留在“Agent 化工作流”。

这也是当前项目代码基础之上，技术风险和收益比最合理的改造方向。
