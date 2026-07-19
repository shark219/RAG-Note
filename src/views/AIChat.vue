<template>
  <div class="ai-chat">
    <!-- 左侧边栏 -->
    <div class="chat-sidebar">
      <div class="sidebar-header">
        <a-button type="primary" long @click="handleNewChat">
          <template #icon><icon-plus /></template>
          新对话
        </a-button>
      </div>
      <div class="sidebar-title">历史对话</div>
      <div class="session-list">
        <div v-if="sessions.length === 0" class="empty-sessions">
          暂无历史对话
        </div>
        <div
          v-for="session in sessions"
          :key="session.session_id"
          class="session-item"
          :class="{ active: currentSessionId === session.session_id }"
          @click="switchSession(session.session_id)"
        >
          <icon-message />
          <span class="session-title">{{ session.title || '新的对话' }}</span>
          <a-button
            type="text"
            size="mini"
            class="delete-btn"
            @click.stop="deleteSession(session.session_id)"
          >
            <icon-delete />
          </a-button>
        </div>
      </div>
    </div>

    <!-- 右侧主区域 -->
    <div class="chat-main">
      <!-- 顶部配置栏 -->
      <div class="config-bar">
        <div class="config-left">
          <a-button type="primary" size="small" @click="handleNewChat">
            <template #icon><icon-plus /></template>
            新对话
          </a-button>
          <a-button size="small" @click="handleSelectAll" v-if="sessions.length > 0">
            <template #icon><icon-check-circle /></template>
            全选
          </a-button>
          <a-button type="outline" status="danger" size="small" @click="handleClearAll" v-if="sessions.length > 0">
            <template #icon><icon-delete /></template>
            清除对话
          </a-button>
        </div>
        <div class="config-right">
          <div class="config-item">
            <span class="config-label">知识库</span>
            <a-switch v-model="config.knowledgeEnabled" size="small" />
          </div>
          <a-divider direction="vertical" />
          <a-select
            v-model="config.selectedPrompt"
            placeholder="选择提示词"
            size="small"
            style="width: 140px"
          >
            <a-option value="default">默认通用提示词</a-option>
            <a-option value="note">笔记助手</a-option>
            <a-option value="knowledge">知识库问答</a-option>
          </a-select>
          <a-divider direction="vertical" />
          <a-button type="text" size="small" @click="$router.push('/system/prompt')">
            <template #icon><icon-file /></template>
            管理提示词
          </a-button>
          <a-button type="text" size="small" @click="$router.push('/system/llm')">
            <template #icon><icon-settings /></template>
            LLM配置
          </a-button>
          <a-button type="text" size="small" @click="showToolApproval = true">
            <template #icon><icon-thunderbolt /></template>
            工具审批
          </a-button>
          <a-button type="text" size="small" @click="handleWechatBind">
            <template #icon><icon-wechat /></template>
            微信接入
          </a-button>
        </div>
      </div>

      <!-- 消息区域 -->
      <div class="chat-messages" ref="messagesRef">
        <!-- 欢迎界面 -->
        <div v-if="messages.length === 0 && !streaming" class="welcome">
          <div class="welcome-logo">
            <icon-robot />
          </div>
          <h2>开始与 RAG Note 的对话吧</h2>
          <p>基于你的笔记和知识库，智能回答问题</p>
          <div class="quick-questions">
            <div
              v-for="q in quickQuestions"
              :key="q"
              class="quick-item"
              @click="sendMessage(q)"
            >
              <icon-send style="font-size: 14px" />
              <span>{{ q }}</span>
            </div>
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-else class="message-list">
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="message-item"
            :class="msg.role === 'human' ? 'user' : 'assistant'"
          >
            <div class="message-avatar">
              <span v-if="msg.role === 'human'">你</span>
              <icon-robot v-else />
            </div>
            <div class="message-content">
              <div class="message-text" v-html="renderMarkdown(msg.content)" />
              <!-- 消息工具栏 -->
              <div class="message-toolbar">
                <span class="message-time">{{ formatTime(msg.created_at) }}</span>
                <a-space :size="4">
                  <a-tooltip content="复制">
                    <a-button type="text" size="mini" @click="handleCopy(msg.content)">
                      <template #icon><icon-copy /></template>
                    </a-button>
                  </a-tooltip>
                  <a-tooltip content="引用">
                    <a-button type="text" size="mini" @click="handleQuote(msg)">
                      <template #icon><icon-quote /></template>
                    </a-button>
                  </a-tooltip>
                  <a-tooltip v-if="msg.role === 'human'" content="修改">
                    <a-button type="text" size="mini" @click="handleEdit(msg)">
                      <template #icon><icon-edit /></template>
                    </a-button>
                  </a-tooltip>
                  <a-tooltip v-if="msg.role === 'ai'" content="重新生成">
                    <a-button type="text" size="mini" @click="handleRegenerate(index)">
                      <template #icon><icon-refresh /></template>
                    </a-button>
                  </a-tooltip>
                  <a-tooltip content="删除">
                    <a-button type="text" size="mini" status="danger" @click="handleDeleteMessage(index)">
                      <template #icon><icon-delete /></template>
                    </a-button>
                  </a-tooltip>
                </a-space>
              </div>
            </div>
          </div>

          <!-- 思考中指示器 -->
          <div v-if="streaming" class="message-item assistant">
            <div class="message-avatar"><icon-robot /></div>
            <div class="message-content">
              <div class="thinking-indicator">
                <div class="thinking-dots">
                  <span></span><span></span><span></span>
                </div>
                <span>正在思考...</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 引用提示 -->
      <div v-if="quoteMessage" class="quote-bar">
        <div class="quote-content">
          <icon-quote style="font-size: 14px; color: var(--color-text-3)" />
          <span class="quote-text">{{ quoteMessage.content.slice(0, 100) }}{{ quoteMessage.content.length > 100 ? '...' : '' }}</span>
          <a-button type="text" size="mini" @click="quoteMessage = null">
            <template #icon><icon-close /></template>
          </a-button>
        </div>
      </div>

      <!-- 输入区域 -->
      <div class="input-area">
        <div class="input-wrapper">
          <a-textarea
            v-model="inputMessage"
            placeholder="请输入你的消息... (Enter发送，Shift+Enter换行)"
            :auto-size="{ minRows: 1, maxRows: 6 }"
            @keydown="handleKeydown"
          />
          <div class="input-footer">
            <div class="input-info">
              <span class="token-count" v-if="tokenUsed > 0">
                Token: {{ tokenUsed }} / {{ tokenMax }}
              </span>
            </div>
            <div class="input-actions">
              <a-button
                v-if="streaming"
                type="outline"
                size="small"
                @click="handleStop"
              >
                <template #icon><icon-stop /></template>
                停止
              </a-button>
              <a-button
                v-else
                type="primary"
                size="small"
                :disabled="!inputMessage.trim()"
                @click="handleSend"
              >
                <template #icon><icon-send /></template>
                发送
              </a-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 工具审批偏好设置弹窗 -->
    <a-modal v-model:visible="showToolApproval" title="工具审批偏好设置" :width="720">
      <div class="tool-approval">
        <a-alert type="info" style="margin-bottom: 16px">
          设置后，系统将自动应用您的审批选择，无需每次手动确认。
        </a-alert>

        <div class="tool-table">
          <div class="tool-table-header">
            <span class="col-tool">工具名称</span>
            <span class="col-action">审批行为</span>
            <span class="col-duration">授权时效</span>
          </div>
          <div v-for="(tool, index) in toolList" :key="index" class="tool-table-row">
            <div class="col-tool">
              <div class="tool-icon" :class="tool.riskLevel">
                <icon-command />
              </div>
              <div>
                <div class="tool-name">{{ tool.name }}</div>
                <div class="tool-desc">{{ tool.description }}</div>
              </div>
            </div>
            <div class="col-action">
              <a-select v-model="tool.approval" size="small" style="width: 130px">
                <a-option value="always_allow">始终允许</a-option>
                <a-option value="always_deny">始终拒绝</a-option>
                <a-option value="ask">每次询问</a-option>
              </a-select>
            </div>
            <div class="col-duration">
              <a-select v-model="tool.duration" size="small" style="width: 130px">
                <a-option value="session">仅本次会话</a-option>
                <a-option value="permanent">永久生效</a-option>
              </a-select>
            </div>
          </div>
        </div>

        <div class="tool-risk-legend">
          <span class="legend-item"><span class="risk-dot high"></span> 高风险</span>
          <span class="legend-item"><span class="risk-dot medium"></span> 中风险</span>
          <span class="legend-item"><span class="risk-dot low"></span> 低风险</span>
        </div>
      </div>

      <template #footer>
        <div class="modal-footer">
          <a-button type="text" status="danger" @click="resetToolApproval">
            <template #icon><icon-refresh /></template>
            重置全部
          </a-button>
          <div>
            <a-button @click="showToolApproval = false">取消</a-button>
            <a-button type="primary" @click="showToolApproval = false; Message.success('已保存')">保存</a-button>
          </div>
        </div>
      </template>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import {
  IconPlus,
  IconMessage,
  IconDelete,
  IconRobot,
  IconSend,
  IconStop,
  IconCopy,
  IconEdit,
  IconQuote,
  IconClose,
  IconRefresh,
  IconCheckCircle,
  IconFile,
  IconSettings,
  IconThunderbolt,
  IconWechat,
  IconCommand,
} from '@arco-design/web-vue/es/icon'
import { chatApi } from '@/api'
import { marked } from 'marked'

const route = useRoute()
const messagesRef = ref<HTMLElement>()
const inputMessage = ref('')
const streaming = ref(false)
const currentSessionId = ref('')
const sessions = ref<any[]>([])
const messages = ref<any[]>([])
const quoteMessage = ref<any>(null)
const showToolApproval = ref(false)
const tokenUsed = ref(0)
const tokenMax = ref(32000)

const config = reactive({
  knowledgeEnabled: true,
  selectedPrompt: 'default',
})

const toolList = ref([
  { name: 'read_skill_content', description: '读取技能/脚本内容', riskLevel: 'low', approval: 'always_allow', duration: 'permanent' },
  { name: 'execute_skill_script', description: '执行技能/脚本', riskLevel: 'high', approval: 'ask', duration: 'permanent' },
  { name: 'create_note', description: '创建新的笔记记录', riskLevel: 'low', approval: 'always_allow', duration: 'permanent' },
  { name: 'delete_note', description: '删除现有笔记', riskLevel: 'high', approval: 'ask', duration: 'permanent' },
  { name: 'send_message', description: '通过微信等渠道发送消息', riskLevel: 'high', approval: 'ask', duration: 'permanent' },
  { name: 'execute_code', description: '运行 Python/JS 代码片段', riskLevel: 'high', approval: 'ask', duration: 'permanent' },
  { name: 'access_web', description: '抓取网页内容', riskLevel: 'medium', approval: 'always_allow', duration: 'permanent' },
  { name: 'file_operation', description: '读写本地文件', riskLevel: 'high', approval: 'ask', duration: 'permanent' },
])

const quickQuestions = [
  '总结一下我的笔记',
  '今天有什么要复习的？',
  '帮我搜索相关笔记',
  '解释一下这个概念',
]

onMounted(() => {
  fetchSessions()
})

// ========== 会话管理 ==========

async function fetchSessions() {
  try {
    const res: any = await chatApi.getSessions()
    const data = res?.data || res
    sessions.value = data?.sessions || []
  } catch (e) {
    console.error('获取会话列表失败', e)
  }
}

async function handleNewChat() {
  try {
    const res: any = await chatApi.createSession()
    const data = res?.data || res
    currentSessionId.value = data?.session_id || ''
    messages.value = []
    tokenUsed.value = 0
    fetchSessions()
  } catch (e) {
    console.error('创建会话失败', e)
    Message.error('创建会话失败')
  }
}

async function switchSession(id: string) {
  currentSessionId.value = id
  await fetchMessages()
}

async function deleteSession(id: string) {
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除这个会话吗？',
    onOk: async () => {
      try {
        await chatApi.deleteSession(id)
        if (currentSessionId.value === id) {
          currentSessionId.value = ''
          messages.value = []
          tokenUsed.value = 0
        }
        fetchSessions()
        Message.success('已删除')
      } catch (e) {
        Message.error('删除失败')
      }
    },
  })
}

function handleSelectAll() {
  // 全选功能
}

function handleWechatBind() {
  Message.info('微信接入功能开发中')
}

function resetToolApproval() {
  Modal.confirm({
    title: '确认重置',
    content: '确定要将所有工具的审批设置恢复为默认值吗？',
    onOk: () => {
      toolList.value.forEach(tool => {
        tool.approval = tool.riskLevel === 'high' ? 'ask' : 'always_allow'
        tool.duration = 'permanent'
      })
      Message.success('已重置为默认设置')
    },
  })
}

function handleClearAll() {
  Modal.confirm({
    title: '确认清除',
    content: '确定要清除所有对话记录吗？',
    onOk: async () => {
      try {
        await chatApi.clearAllSessions()
        currentSessionId.value = ''
        messages.value = []
        tokenUsed.value = 0
        fetchSessions()
        Message.success('已清除所有对话')
      } catch (e) {
        Message.error('清除失败')
      }
    },
  })
}

// ========== 消息管理 ==========

async function fetchMessages() {
  if (!currentSessionId.value) {
    messages.value = []
    return
  }
  try {
    const res: any = await chatApi.getSession(currentSessionId.value)
    const data = res?.data || res
    messages.value = data?.messages || []
    scrollToBottom()
    fetchTokenUsage()
  } catch (e) {
    console.error('获取消息失败', e)
  }
}

async function fetchTokenUsage() {
  if (!currentSessionId.value) return
  try {
    const res: any = await chatApi.getSessionTokens(currentSessionId.value)
    const data = res?.data || res
    tokenUsed.value = data?.used || 0
    tokenMax.value = data?.max || 32000
  } catch (e) {
    console.error('获取 Token 用量失败', e)
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

async function handleSend() {
  const text = inputMessage.value.trim()
  if (!text || streaming.value) return
  await sendMessage(text)
}

async function sendMessage(text: string) {
  // 如果没有会话，先创建
  if (!currentSessionId.value) {
    try {
      const res: any = await chatApi.createSession()
      const data = res?.data || res
      currentSessionId.value = data?.session_id || ''
    } catch (e) {
      Message.error('创建会话失败')
      return
    }
  }

  // 构建消息内容
  let displayContent = text
  let sendContent = text

  if (quoteMessage.value) {
    const quoteText = quoteMessage.value.content.slice(0, 200)
    displayContent = `> 引用: ${quoteText}\n\n${text}`
    sendContent = `[引用: "${quoteText}"]\n\n${text}`
    quoteMessage.value = null
  }

  // 添加用户消息
  messages.value.push({ role: 'human', content: displayContent })
  inputMessage.value = ''
  streaming.value = true
  scrollToBottom()

  try {
    const response = await chatApi.sendStream({
      query: sendContent,
      session_id: currentSessionId.value,
    })

    const reader = response.body?.getReader()
    if (!reader) {
      streaming.value = false
      return
    }

    let assistantMessage = ''
    let traceId = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      const chunk = new TextDecoder().decode(value)
      const lines = chunk.split('\n')

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const data = JSON.parse(line.slice(6))
            if (data.type === 'response') {
              assistantMessage += data.content
            } else if (data.type === 'done') {
              traceId = data.trace_id || ''
              if (data.session_id) {
                currentSessionId.value = data.session_id
              }
            }
          } catch (e) {
            // 忽略解析错误
          }
        }
      }

      // 更新或添加AI消息
      if (assistantMessage) {
        const lastMsg = messages.value[messages.value.length - 1]
        if (lastMsg?.role === 'ai') {
          lastMsg.content = assistantMessage
        } else {
          messages.value.push({ role: 'ai', content: assistantMessage, traceId })
        }
        scrollToBottom()
      }
    }

    // 流式完成后刷新会话列表
    setTimeout(() => {
      fetchSessions()
      fetchTokenUsage()
    }, 500)
  } catch (e: any) {
    console.error('发送失败', e)
    Message.error(e.message || '发送失败')
  } finally {
    streaming.value = false
  }
}

function handleStop() {
  streaming.value = false
  Message.info('已停止生成')
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

// ========== 消息操作 ==========

function renderMarkdown(text: string) {
  return marked(text || '')
}

function handleCopy(text: string) {
  navigator.clipboard.writeText(text)
  Message.success('已复制到剪贴板')
}

function handleEdit(msg: any) {
  inputMessage.value = msg.content
  Message.info('内容已回到输入框')
}

function handleQuote(msg: any) {
  quoteMessage.value = msg
  Message.info('已引用消息')
}

async function handleRegenerate(index: number) {
  let userQuery = ''
  for (let i = index - 1; i >= 0; i--) {
    if (messages.value[i].role === 'human') {
      userQuery = messages.value[i].content
      break
    }
  }
  if (!userQuery) {
    Message.warning('找不到对应的用户问题')
    return
  }
  messages.value.splice(index, 1)
  await sendMessage(userQuery)
}

function handleDeleteMessage(index: number) {
  const msg = messages.value[index]
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除这条消息吗？',
    onOk: () => {
      if (msg.role === 'human' && index + 1 < messages.value.length && messages.value[index + 1].role === 'ai') {
        messages.value.splice(index, 2)
      } else {
        messages.value.splice(index, 1)
      }
      Message.success('已删除')
    },
  })
}

function formatTime(dateStr: string) {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))

  if (days === 0) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } else if (days === 1) {
    return '昨天 ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } else {
    return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
  }
}
</script>

<style scoped>
.ai-chat {
  display: flex;
  height: calc(100vh - 92px);
  background: var(--color-bg-1);
  border-radius: 8px;
  overflow: hidden;
}

/* 左侧边栏 */
.chat-sidebar {
  width: 260px;
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  background: var(--color-bg-2);
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.sidebar-title {
  padding: 12px 16px 8px;
  font-size: 12px;
  color: var(--color-text-3);
  font-weight: 500;
  flex-shrink: 0;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.empty-sessions {
  text-align: center;
  color: var(--color-text-3);
  padding: 40px 16px;
  font-size: 13px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  margin-bottom: 2px;
}

.session-item:hover {
  background: var(--color-bg-3);
}

.session-item.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.session-item .delete-btn {
  opacity: 0;
  transition: opacity 0.2s;
}

.session-item:hover .delete-btn {
  opacity: 1;
}

.session-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

/* 右侧主区域 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
}

/* 配置栏 */
.config-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 20px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-1);
  flex-shrink: 0;
}

.config-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.config-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-label {
  font-size: 13px;
  color: var(--color-text-2);
}

/* 工具审批弹窗样式 */
.tool-approval {
  max-height: 500px;
  overflow-y: auto;
}

.tool-table {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
}

.tool-table-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: var(--color-bg-2);
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-2);
  border-bottom: 1px solid var(--color-border);
}

.tool-table-row {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
}

.tool-table-row:last-child {
  border-bottom: none;
}

.col-tool {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.col-action {
  width: 140px;
  margin: 0 12px;
}

.col-duration {
  width: 140px;
}

.tool-icon {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.tool-icon.high {
  background: #fff0f0;
  color: #f53f3f;
}

.tool-icon.medium {
  background: #fff7e8;
  color: #ff7d00;
}

.tool-icon.low {
  background: #e8ffea;
  color: #00b42a;
}

.tool-name {
  font-weight: 500;
  font-size: 13px;
  font-family: monospace;
}

.tool-desc {
  font-size: 12px;
  color: var(--color-text-3);
  margin-top: 2px;
}

.tool-risk-legend {
  display: flex;
  gap: 16px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-text-3);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.risk-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.risk-dot.high {
  background: #f53f3f;
}

.risk-dot.medium {
  background: #ff7d00;
}

.risk-dot.low {
  background: #00b42a;
}

.modal-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

/* 消息区域 */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

/* 欢迎界面 */
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--color-text-3);
}

.welcome-logo {
  width: 80px;
  height: 80px;
  border-radius: 20px;
  background: linear-gradient(135deg, #165dff 0%, #722ed1 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}

.welcome-logo :deep(svg) {
  font-size: 40px;
  color: #fff;
}

.welcome h2 {
  font-size: 20px;
  color: var(--color-text-1);
  margin: 0 0 8px;
}

.welcome p {
  margin: 0 0 24px;
}

.quick-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  max-width: 500px;
}

.quick-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 13px;
}

.quick-item:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--color-primary-light);
}

/* 消息样式 */
.message-list {
  max-width: 900px;
  margin: 0 auto;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-3);
  flex-shrink: 0;
}

.message-item.assistant .message-avatar {
  background: linear-gradient(135deg, #165dff 0%, #722ed1 100%);
  color: #fff;
}

.message-content {
  max-width: 75%;
  min-width: 60px;
}

.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.7;
  background: var(--color-bg-2);
  word-break: break-word;
}

.message-item.user .message-text {
  background: var(--color-primary);
  color: #fff;
}

.message-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  opacity: 0;
  transition: opacity 0.2s;
}

.message-item:hover .message-toolbar {
  opacity: 1;
}

.message-time {
  font-size: 11px;
  color: var(--color-text-3);
}

.message-avatar span {
  font-size: 12px;
  font-weight: 600;
  color: #fff;
}

/* 思考动画 */
.thinking-indicator {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: var(--color-bg-2);
  border-radius: 12px;
}

.thinking-dots {
  display: flex;
  gap: 4px;
}

.thinking-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
  animation: thinking 1.4s infinite ease-in-out both;
}

.thinking-dots span:nth-child(1) { animation-delay: -0.32s; }
.thinking-dots span:nth-child(2) { animation-delay: -0.16s; }

@keyframes thinking {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

/* 引用栏 */
.quote-bar {
  padding: 8px 20px;
  flex-shrink: 0;
}

.quote-content {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--color-bg-2);
  border-left: 3px solid var(--color-primary);
  border-radius: 4px;
  max-width: 900px;
  margin: 0 auto;
}

.quote-text {
  flex: 1;
  font-size: 12px;
  color: var(--color-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 输入区域 */
.input-area {
  padding: 16px 20px;
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
}

.input-wrapper {
  max-width: 900px;
  margin: 0 auto;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  overflow: hidden;
  transition: border-color 0.2s;
}

.input-wrapper:focus-within {
  border-color: var(--color-primary);
}

.input-wrapper :deep(.arco-textarea-wrapper) {
  border: none;
  border-radius: 0;
}

.input-wrapper :deep(.arco-textarea) {
  padding: 12px 16px;
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--color-bg-2);
}

.input-info {
  font-size: 12px;
  color: var(--color-text-3);
}

.input-actions {
  display: flex;
  gap: 8px;
}
</style>
