#!/bin/bash
# AI Agent 功能自动化测试脚本
# 用法: bash test-agent.sh [BASE_URL] [TOKEN]

BASE_URL="${1:-http://localhost:8080}"
TOKEN="${2:-}"

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0
TOTAL=0

# 获取 Token
if [ -z "$TOKEN" ]; then
  echo -e "${YELLOW}正在登录获取 Token...${NC}"
  TOKEN=$(curl -s -X POST "$BASE_URL/user/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"test","password":"123456"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
  if [ -z "$TOKEN" ]; then
    echo -e "${RED}登录失败，请手动提供 Token${NC}"
    echo "用法: bash test-agent.sh http://localhost:8080 YOUR_TOKEN"
    exit 1
  fi
  echo -e "${GREEN}Token 获取成功${NC}"
fi

# 测试函数：发送查询并检查 SSE 响应
test_query() {
  local name="$1"
  local query="$2"
  local expect_tool="$3"  # 期望调用的工具名（可选）
  local expect_contains="$4"  # 期望响应包含的关键词（可选）

  TOTAL=$((TOTAL + 1))
  echo -e "\n${YELLOW}[$TOTAL] $name${NC}"
  echo "  查询: $query"

  # 发送请求，收集 SSE 事件
  response=$(curl -s -N -X POST "$BASE_URL/chat/agent/query/stream" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"query\":\"$query\"}" --max-time 30 2>/dev/null)

  # 提取最终回复
  final_answer=$(echo "$response" | grep '"type":"response"' | sed 's/.*"content":"\([^"]*\)".*/\1/' | tr -d '\n')
  tool_calls=$(echo "$response" | grep '"type":"thinking"' | grep -o '"content":"[^"]*"' | sed 's/"content":"//;s/"//')
  has_error=$(echo "$response" | grep '"type":"error"')

  # 检查是否有错误
  if [ -n "$has_error" ]; then
    echo -e "  ${RED}✗ 响应包含错误${NC}"
    FAIL=$((FAIL + 1))
    return
  fi

  # 检查是否有回复
  if [ -z "$final_answer" ]; then
    # 尝试从 response 事件中提取
    final_answer=$(echo "$response" | grep '"type":"response"' | head -1 | sed 's/.*"content":"\([^"]*\)".*/\1/')
  fi

  if [ -z "$final_answer" ]; then
    echo -e "  ${RED}✗ 未收到回复${NC}"
    FAIL=$((FAIL + 1))
    return
  fi

  # 检查工具调用
  if [ -n "$expect_tool" ]; then
    if echo "$tool_calls" | grep -qi "$expect_tool"; then
      echo -e "  ${GREEN}✓ 工具调用: $expect_tool${NC}"
    else
      echo -e "  ${RED}✗ 未调用期望的工具: $expect_tool${NC}"
      echo "  实际工具调用: $tool_calls"
      FAIL=$((FAIL + 1))
      return
    fi
  fi

  # 检查关键词
  if [ -n "$expect_contains" ]; then
    if echo "$final_answer" | grep -qi "$expect_contains"; then
      echo -e "  ${GREEN}✓ 回答包含期望关键词: $expect_contains${NC}"
    else
      echo -e "  ${YELLOW}⚠ 回答未包含期望关键词: $expect_contains${NC}"
    fi
  fi

  echo -e "  ${GREEN}✓ 测试通过${NC}"
  PASS=$((PASS + 1))
}

echo "=========================================="
echo "  AI Agent 功能测试"
echo "=========================================="

# ---- 一、意图识别测试 ----
echo -e "\n${YELLOW}=== 一、意图识别测试 ===${NC}"

test_query "清晰查询 - 笔记搜索" \
  "搜索我笔记里关于 BM25 的内容" \
  "searchNotes"

test_query "清晰查询 - 知识库检索" \
  "知识库里关于 RAG 的定义是什么" \
  "ragSummary"

test_query "通用知识 - 不调工具" \
  "什么是 Transformer 架构" \
  "" "Transformer"

test_query "多意图混合" \
  "搜索笔记里关于 AI 的内容，再统计一下笔记总数" \
  "searchNotes"

# ---- 二、工具调用测试 ----
echo -e "\n${YELLOW}=== 二、工具调用测试 ===${NC}"

test_query "笔记搜索" \
  "搜索我的笔记" \
  "searchNotes"

test_query "笔记统计" \
  "我有多少篇笔记" \
  "getNoteStats"

test_query "今日复习" \
  "今天有什么要复习的" \
  "getTodayReviews"

test_query "相关笔记" \
  "帮我找和 RAG 相关的笔记" \
  "searchNotes"

# ---- 三、记忆管理测试 ----
echo -e "\n${YELLOW}=== 三、记忆管理测试 ===${NC}"

test_query "短期记忆 - 第一轮" \
  "什么是 BM25 算法" \
  "" "BM25"

test_query "短期记忆 - 第二轮（代词引用）" \
  "它和 TF-IDF 有什么区别" \
  "" "TF-IDF"

# ---- 四、RAG 质量测试 ----
echo -e "\n${YELLOW}=== 四、RAG 质量测试 ===${NC}"

test_query "知识库检索" \
  "根据知识库，RAG 的工作原理是什么" \
  "ragSummary"

test_query "笔记 + 知识库联合" \
  "结合我的笔记和知识库，总结一下 RAG 的核心概念" \
  "ragSummary"

# ---- 五、边界情况测试 ----
echo -e "\n${YELLOW}=== 五、边界情况测试 ===${NC}"

test_query "简单数学" \
  "1+1等于几" \
  "" "2"

test_query "闲聊" \
  "你好" \
  "" "你好"

# ---- 结果汇总 ----
echo -e "\n=========================================="
echo -e "  测试结果: ${GREEN}$PASS 通过${NC} / ${RED}$FAIL 失败${NC} / 共 $TOTAL 个"
echo -e "=========================================="

if [ $FAIL -eq 0 ]; then
  echo -e "${GREEN}所有测试通过！${NC}"
  exit 0
else
  echo -e "${RED}有 $FAIL 个测试失败，请检查${NC}"
  exit 1
fi
