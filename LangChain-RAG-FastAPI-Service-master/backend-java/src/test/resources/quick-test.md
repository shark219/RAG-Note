# 快速测试指南

## 前置条件

1. 后端服务已启动
2. 已注册账号并获取 Token

## 获取 Token

```bash
curl -X POST http://localhost:8080/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"your_username","password":"your_password"}'
```

## 测试命令（复制粘贴即可）

### 设置 Token（替换 YOUR_TOKEN）

```bash
TOKEN="YOUR_TOKEN"
```

### 测试 1: 意图识别 — 清晰查询

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"搜索我笔记里关于 BM25 的内容"}'
```

**看什么**: 是否调用了 searchNotes 工具，返回相关笔记

### 测试 2: 意图识别 — 模糊查询

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"帮我学习AI"}'
```

**看什么**: 是否返回澄清方向选项，或给出宽泛但合理的回答

### 测试 3: 多工具调用

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"搜索笔记里关于 AI 的内容，再统计一下我有多少笔记"}'
```

**看什么**: 是否调用了 searchNotes + getNoteStats 两个工具

### 测试 4: 知识库检索

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"知识库里关于 RAG 的内容是什么"}'
```

**看什么**: 是否调用 ragSummary，回答是否基于知识库文档

### 测试 5: 创建笔记

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"帮我创建一篇笔记，标题是测试笔记，内容是今天学习了RAG"}'
```

**看什么**: 是否调用 createNote，笔记是否创建成功

### 测试 6: 通用知识（不调工具）

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"什么是 Transformer 架构"}'
```

**看什么**: 是否直接回答，不调用任何工具

### 测试 7: 上下文记忆

```bash
# 第一轮
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"什么是 BM25 算法"}'

# 第二轮（需要带上 sessionId）
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"它和 TF-IDF 有什么区别","sessionId":"上一轮返回的session_id"}'
```

**看什么**: 第二轮是否理解"它"指的是 BM25

### 测试 8: 复杂任务（多 Agent 流水线）

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"帮我整理 AI 学习笔记，总结知识库内容，看看今天要复习什么"}'
```

**看什么**: 思考过程中是否出现"规划→研究→写作"阶段

### 测试 9: 今日复习

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"今天有什么要复习的笔记"}'
```

**看什么**: 是否调用 getTodayReviews，返回复习列表

### 测试 10: 边界 — 空查询

```bash
curl -s -N -X POST http://localhost:8080/chat/agent/query/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":""}'
```

**看什么**: 是否返回错误提示，不崩溃

## SSE 事件说明

每个请求会返回多个 SSE 事件：

```
data: {"type":"thinking","stage":"planning","content":"..."}   # 规划阶段
data: {"type":"thinking","stage":"tool_call","content":"..."}  # 工具调用
data: {"type":"thinking","stage":"researching","content":"..."} # 研究中
data: {"type":"thinking","stage":"writing","content":"..."}    # 写作中
data: {"type":"thinking","stage":"review","content":"..."}     # 质量审查
data: {"type":"thinking","stage":"complete","content":"..."}   # 完成
data: {"type":"response","content":"回答内容..."}               # 最终回答
data: {"type":"done","session_id":"...","trace_id":"..."}      # 结束
```

## 自动化测试

```bash
bash test-agent.sh http://localhost:8080 YOUR_TOKEN
```
