<template>
  <div class="ai-chat">
    <!-- 左侧边栏 -->
    <div class="chat-sidebar">
      <div class="sidebar-header">
        <div class="config-left">
          <a-button type="primary" size="small" @click="handleNewChat">
            <template #icon><icon-plus /></template>
          </a-button>
          <a-button size="small" @click="handleSelectAll" v-if="sessions.length > 0">
            <template #icon><icon-check-circle /></template>
            全选
          </a-button>
          <a-button type="outline" status="danger" size="small" @click="handleClearAll" v-if="sessions.length > 0">
            <template #icon><icon-delete /></template>
            清空
          </a-button>
        </div>
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

        <div class="config-right">
          <div class="config-item" style="flex-wrap: wrap; gap: 4px;">
            <span class="config-label">知识库</span>
            <a-switch v-model="config.knowledgeEnabled" size="small" />
            <a-select
              v-model="config.selectedKnowledgeDocs"
              placeholder="选择文档"
              multiple
              :filterable="true"
              :allow-clear="true"
              size="small"
              style="width: 180px; margin-left: 4px;"
              :disabled="!config.knowledgeEnabled"
            >
              <a-option
                v-for="doc in knowledgeDocs"
                :key="doc.filename"
                :value="doc.filename"
                :label="doc.originalFilename"
              />
            </a-select>
          </div>
          <a-divider direction="vertical" />
          <div class="config-item" style="flex-wrap: wrap; gap: 4px;">
            <span class="config-label">笔记</span>
            <a-switch v-model="config.notesEnabled" size="small" />
            <a-select
              v-model="config.selectedNotes"
              placeholder="选择笔记"
              multiple
              :filterable="true"
              :allow-clear="true"
              size="small"
              style="width: 180px; margin-left: 4px;"
              :disabled="!config.notesEnabled"
            >
              <a-option
                v-for="note in notesList"
                :key="note.id"
                :value="note.id"
                :label="note.title"
              />
            </a-select>
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
              <span v-if="msg.role === 'human'">用户</span>
              <icon-robot v-else />
            </div>
            <div class="message-content">
              <!-- 思考过程区域 -->
              <div v-if="msg.thinking" class="thinking-section">
                <div class="thinking-header" @click="msg.thinkingCollapsed = !msg.thinkingCollapsed">
                  <span class="thinking-label">思考过程</span>
                  <span class="thinking-toggle">{{ msg.thinkingCollapsed ? '展开' : '收起' }}</span>
                </div>
                <div v-show="!msg.thinkingCollapsed" class="thinking-body">
                  <div v-for="(step, sIndex) in msg.thinking" :key="sIndex" class="thinking-step">
                    <span class="thinking-stage-label" :style="{ backgroundColor: getStageColor(step.stage) }">
                      {{ getStageLabel(step.stage) }}
                    </span>
                    <span class="thinking-step-content">{{ step.content }}</span>
                  </div>
                </div>
              </div>
              <!-- 回复正文 -->
              <div v-if="msg.content" class="message-text" v-html="renderMarkdown(msg.content)" />
              <!-- 产物渲染（思维导图、图表等） -->
              <div v-if="msg.artifacts && msg.artifacts.length > 0" class="artifact-section">
                <div v-for="art in msg.artifacts" :key="art.id" class="artifact-card">
                  <div class="artifact-header">
                    <span class="artifact-icon">{{ artifactIcon(art.type) }}</span>
                    <span class="artifact-label">{{ art.label || art.type }}</span>
                    <a-button type="text" size="mini" @click="toggleArtifactCollapse(art.id)">
                      {{ collapsedArtifacts[art.id] ? '展开' : '收起' }}
                    </a-button>
                  </div>
                  <div v-show="!collapsedArtifacts[art.id]" class="artifact-body">
                    <div v-if="art.type === 'mindmap' || art.type === 'diagram'" class="artifact-markdown"
                         v-html="renderMarkdown(art.content || '')" />
                    <div v-else class="artifact-raw">{{ art.content }}</div>
                  </div>
                </div>
              </div>
              <!-- 用户反馈按钮 -->
              <div v-if="msg.role === 'ai' && msg.content && msg.traceId && !msg.feedback" class="feedback-bar">
                <span class="feedback-btn" @click="submitFeedback(msg, 5)">👍</span>
                <span class="feedback-btn" @click="submitFeedback(msg, 1)">👎</span>
              </div>
              <div v-if="msg.feedback" class="feedback-thanks">
                {{ msg.feedback >= 4 ? '感谢反馈！' : '感谢反馈，我们会持续优化' }}
              </div>
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

      <!-- 附件卡片 -->
      <div v-if="attachments.length > 0" class="attachments-bar">
        <div v-for="(att, index) in attachments" :key="att.id" class="attachment-card">
          <icon-file />
          <span class="attachment-name">{{ att.name }}</span>
          <span class="attachment-size">{{ formatFileSize(att.size) }}</span>
          <a-button type="text" size="mini" @click="removeAttachment(index)">
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
              <input ref="fileInputRef" type="file" style="display: none" @change="handleFileUpload" />
              <a-button type="text" size="mini" :loading="uploading" @click="triggerFileUpload">
                <template #icon><icon-attachment /></template>
                附件
              </a-button>
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
import { ref, reactive, onMounted, nextTick } from 'vue'
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
  IconCommand,
  IconAttachment,
} from '@arco-design/web-vue/es/icon'
import { chatApi, evaluationApi, knowledgeApi, noteApi } from '@/api'
import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'

marked.use(markedHighlight({
  langPrefix: 'hljs language-',
  highlight(code: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return code
  },
}))

const messagesRef = ref<HTMLElement>()
const inputMessage = ref('')
const streaming = ref(false)
const currentSessionId = ref('')
const sessions = ref<any[]>([])
const messages = ref<any[]>([])
const quoteMessage = ref<any>(null)
const showToolApproval = ref(false)
let stopFlag = false
const tokenUsed = ref(0)
const tokenMax = ref(32000)

// 产物展开/收起状态
const collapsedArtifacts = ref<Record<string, boolean>>({})
function toggleArtifactCollapse(id: string) {
  collapsedArtifacts.value[id] = !collapsedArtifacts.value[id]
}
function artifactIcon(type: string) {
  const icons: Record<string, string> = {
    mindmap: '🧠',
    diagram: '📊',
    note: '📝',
  }
  return icons[type] || '📦'
}

// 知识库文档和笔记列表（供多选下拉使用）
const knowledgeDocs = ref<{ filename: string; originalFilename: string }[]>([])
const notesList = ref<{ id: string; title: string }[]>([])

const config = reactive({
  knowledgeEnabled: true,
  notesEnabled: true,
  selectedPrompt: 'default',
  selectedKnowledgeDocs: [] as string[],
  selectedNotes: [] as string[],
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

// 思考过程阶段配置
const stageConfig: Record<string, { label: string; color: string }> = {
  retrieval:   { label: '检索',   color: '#B8926E' },
  hyde:        { label: 'HyDE',   color: '#8B7E6F' },
  reorder:     { label: '重排序', color: '#D4914A' },
  summarize:   { label: '总结',   color: '#7D9B7A' },
  planning:    { label: '规划',   color: '#6B8EAE' },
  researching: { label: '研究',   color: '#8B7E6F' },
  writing:     { label: '写作',   color: '#7D9B7A' },
  tool_call:   { label: '工具',   color: '#D4914A' },
  review:      { label: '审查',   color: '#C47D5A' },
  complete:    { label: '完成',   color: '#5A8F6A' },
}

function getStageLabel(stage: string) {
  return stageConfig[stage]?.label || stage || '处理中'
}

function getStageColor(stage: string) {
  return stageConfig[stage]?.color || '#999'
}

// ========== 思考过程缓存（localStorage，最近 5 条） ==========

const THINKING_CACHE_KEY = 'ai_thinking_cache'

function saveThinkingToCache(sessionId: string, query: string, thinking: any[]) {
  if (!sessionId || !thinking || thinking.length === 0) return
  try {
    let cache = JSON.parse(localStorage.getItem(THINKING_CACHE_KEY) || '[]')
    cache = cache.filter((e: any) => e.sessionId !== sessionId || e.query !== query)
    cache.unshift({ sessionId, query, thinking, timestamp: Date.now() })
    localStorage.setItem(THINKING_CACHE_KEY, JSON.stringify(cache.slice(0, 5)))
  } catch (e) { /* ignore */ }
}

function loadThinkingFromCache(sessionId: string, query: string): any[] | null {
  if (!sessionId) return null
  try {
    const cache = JSON.parse(localStorage.getItem(THINKING_CACHE_KEY) || '[]')
    const entry = cache.find((e: any) => e.sessionId === sessionId && e.query === query)
    return entry?.thinking || null
  } catch (e) {
    return null
  }
}

// ========== 附件上传 ==========

const fileInputRef = ref<HTMLInputElement>()
const uploading = ref(false)
const attachments = ref<{ id: string; name: string; size: number }[]>([])

function triggerFileUpload() {
  fileInputRef.value?.click()
}

async function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  // 确保有会话
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

  uploading.value = true
  try {
    const res: any = await chatApi.uploadAttachment(file, currentSessionId.value)
    const data = res?.data || res
    const fileId = data?.id
    const fileName = data?.name || file.name
    const fileSize = data?.size || file.size

    if (fileId) {
      attachments.value.push({ id: fileId, name: fileName, size: fileSize })
    }
    Message.success('附件上传成功')
  } catch (e) {
    Message.error('附件上传失败')
  } finally {
    uploading.value = false
    if (target) target.value = ''
  }
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1)
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
}

onMounted(async () => {
  await fetchSessions()
  await loadKnowledgeDocs()
  await loadNotesList()
  // 页面刷新或切换到对话页面时，默认加载最近一次会话
  if (sessions.value.length > 0 && !currentSessionId.value) {
    const latestSession = sessions.value[0]
    currentSessionId.value = latestSession.session_id
    await fetchMessages()
  }
})

async function loadKnowledgeDocs() {
  try {
    const res: any = await knowledgeApi.list()
    const data = res?.data || res
    knowledgeDocs.value = data?.documents?.map((d: any) => ({
      filename: d.filename,
      originalFilename: d.originalFilename || d.filename,
    })) || []
  } catch (e) {
    console.error('获取知识库文档列表失败', e)
  }
}

async function loadNotesList() {
  try {
    const res: any = await noteApi.list({ pageSize: 999 })
    const data = res?.data || res
    notesList.value = data?.notes?.map((n: any) => ({
      id: n.id,
      title: n.title,
    })) || []
  } catch (e) {
    console.error('获取笔记列表失败', e)
  }
}

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
    const rawMessages = data?.messages || []
    // 为历史消息添加默认字段，从缓存恢复思考过程
    messages.value = rawMessages.map((m: any, idx: number) => {
      const msg: any = { ...m, id: m.id }
      if (m.feedback) msg.feedback = m.feedback
      if (m.traceId) msg.traceId = m.traceId
      // AI 回复：尝试从 localStorage 缓存恢复思考过程
      if (m.role === 'ai') {
        const prevUserMsg = idx > 0 && rawMessages[idx - 1]?.role === 'human' ? rawMessages[idx - 1] : null
        const cachedThinking = prevUserMsg ? loadThinkingFromCache(currentSessionId.value, prevUserMsg.content) : null
        if (cachedThinking && cachedThinking.length > 0) {
          msg.thinking = cachedThinking
          msg.thinkingCollapsed = true
        }
      }
      return msg
    })
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

async function sendMessage(text: string, options?: { skipUserMessage?: boolean; regenerate?: boolean }) {
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
  const sendContent = text  // 后端只接收用户实际问题

  if (quoteMessage.value) {
    const quoteText = quoteMessage.value.content.slice(0, 200)
    displayContent = `> 引用: ${quoteText}\n\n${text}`
    quoteMessage.value = null
  }

  // 添加用户消息（重新生成时跳过，因为用户消息已存在）
  if (!options?.skipUserMessage) {
    messages.value.push({ role: 'human', content: displayContent })
  }
  // 添加 AI 消息占位（含思考过程数组）
  messages.value.push({
    role: 'ai',
    content: '',
    thinking: [] as any[],
    thinkingCollapsed: false,
    traceId: '',
    feedback: null as number | null,
  })
  inputMessage.value = ''
  streaming.value = true
  stopFlag = false
  scrollToBottom()

  try {
    const fileIds = attachments.value.map(a => a.id)
    attachments.value = []

    const response = await chatApi.sendStream({
      query: sendContent,
      sessionId: currentSessionId.value,
      enableKnowledge: config.knowledgeEnabled,
      enableNotes: config.notesEnabled,
      selectedKnowledgeDocs: config.selectedKnowledgeDocs.length > 0 ? config.selectedKnowledgeDocs : undefined,
      selectedNotes: config.selectedNotes.length > 0 ? config.selectedNotes : undefined,
      ...(fileIds.length > 0 ? { fileIds } : {}),
      ...(options?.regenerate ? { regenerate: true } : {}),
    })

    const reader = response.body?.getReader()
    if (!reader) {
      streaming.value = false
      return
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let aiResponse = ''
    let traceId = ''
    let autoCollapseTimer: ReturnType<typeof setTimeout> | null = null

    const lastMsg = () => messages.value[messages.value.length - 1]

    while (!stopFlag) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        try {
          const jsonStr = line.startsWith('data: ') ? line.slice(6) : line.slice(5)
          if (!jsonStr.trim()) continue
          const data = JSON.parse(jsonStr)

          switch (data.type) {
            case 'thinking': {
              const msg = lastMsg()
              if (msg?.role === 'ai') {
                msg.thinking = [...msg.thinking, {
                  stage: data.stage || '',
                  content: data.content || '',
                }]
                await nextTick()
                scrollToBottom()
              }
              break
            }
            case 'response': {
              const content = data.content || ''
              if (!content) break
              aiResponse += content

              // 第一条 response 到达时延迟折叠思考过程
              const msg = lastMsg()
              if (msg?.thinking?.length > 0 && !msg._thinkingAutoCollapsed) {
                msg._thinkingAutoCollapsed = true
                if (autoCollapseTimer) clearTimeout(autoCollapseTimer)
                autoCollapseTimer = setTimeout(() => {
                  msg.thinkingCollapsed = true
                }, 1500)
              }

              // 打字机效果：逐字符追加
              const displayed = msg.content || ''
              const remaining = aiResponse.substring(displayed.length)
              for (const char of remaining) {
                if (stopFlag) break
                msg.content += char
                scrollToBottom()
                await new Promise(r => setTimeout(r, 8))
              }
              break
            }
            case 'done': {
              traceId = data.trace_id || ''
              if (data.session_id) {
                currentSessionId.value = data.session_id
              }
              // 更新 token 用量
              if (data.token_used !== undefined) tokenUsed.value = data.token_used
              if (data.token_max !== undefined) tokenMax.value = data.token_max
              // 保存 traceId 和产物到消息
              const msg = lastMsg()
              if (msg?.role === 'ai') {
                if (traceId) msg.traceId = traceId
                // 产物数据（思维导图、图表等）
                if (data.artifacts && Array.isArray(data.artifacts) && data.artifacts.length > 0) {
                  msg.artifacts = data.artifacts
                }
              }
              break
            }
            case 'error': {
              const msg = lastMsg()
              if (msg?.role === 'ai') {
                msg.content = data.content || '处理请求时发生错误'
              }
              break
            }
          }
        } catch (e) {
          console.warn('SSE parse error:', line, e)
        }
      }
    }

    // 流式结束后，确保 AI 消息有内容
    const msg = lastMsg()
    if (msg?.role === 'ai' && !msg.content) {
      msg.content = '抱歉，未能找到相关信息来回答您的问题。'
    }

    // 缓存思考过程到 localStorage
    if (msg?.role === 'ai' && msg.thinking?.length > 0) {
      saveThinkingToCache(currentSessionId.value, text, msg.thinking)
    }

    if (autoCollapseTimer) clearTimeout(autoCollapseTimer)

    // 流式完成后刷新会话列表和消息ID（保留产物数据避免被服务端覆盖）
    const savedArtifacts: Record<number, any[]> = {}
    messages.value.forEach((m: any, i: number) => {
      if (m.role === 'ai' && m.artifacts?.length > 0) {
        savedArtifacts[i] = [...m.artifacts]
      }
    })
    setTimeout(async () => {
      await fetchSessions()
      fetchTokenUsage()
      await fetchMessages()
      // 恢复产物数据
      Object.entries(savedArtifacts).forEach(([idx, arts]) => {
        const i = Number(idx)
        if (i < messages.value.length) {
          const m = messages.value[i] as any
          if (m.role === 'ai') m.artifacts = arts
        }
      })
    }, 500)
  } catch (e: any) {
    console.error('发送失败', e)
    Message.error(e.message || '发送失败')
    // 确保 AI 占位消息有错误内容
    const msg = messages.value[messages.value.length - 1]
    if (msg?.role === 'ai' && !msg.content) {
      msg.content = '发送失败，请稍后重试。'
    }
  } finally {
    streaming.value = false
  }
}

function handleStop() {
  stopFlag = true
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
  const aiMsg = messages.value[index]
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
  // 从数据库删除旧的 AI 回复
  if (aiMsg?.id) {
    try { await chatApi.deleteMessage(aiMsg.id) } catch (e) { /* ignore */ }
  }
  messages.value.splice(index, 1)
  await sendMessage(userQuery, { skipUserMessage: true, regenerate: true })
}

// 提交用户反馈
async function submitFeedback(msg: any, score: number) {
  if (!msg.traceId) {
    Message.warning('反馈失败：缺少 TraceID')
    return
  }
  try {
    const res: any = await evaluationApi.submitFeedback({
      traceId: msg.traceId,
      score,
      reason: score <= 2 ? '回答不准确' : '',
    })
    if (res?.code === 200 || res?.success) {
      msg.feedback = score
      Message.success(score >= 4 ? '感谢鼓励！' : '感谢反馈，我们会持续优化')
    } else {
      Message.error('反馈失败')
    }
  } catch (e) {
    Message.error('反馈失败')
  }
}

function handleDeleteMessage(index: number) {
  const msg = messages.value[index]
  // 找到配对消息
  let pairedIndex = -1
  if (msg.role === 'human' && index + 1 < messages.value.length && messages.value[index + 1].role === 'ai') {
    pairedIndex = index + 1
  } else if (msg.role === 'ai' && index - 1 >= 0 && messages.value[index - 1].role === 'human') {
    pairedIndex = index - 1
  }

  const confirmText = msg.role === 'human'
    ? '确定要删除这条消息与对应的回答消息吗？'
    : '确定要删除这条消息与对应的提问消息吗？'

  Modal.confirm({
    title: '确认删除',
    content: pairedIndex >= 0 ? confirmText : '确定要删除这条消息吗？',
    onOk: async () => {
      // 从数据库删除
      const idsToDelete: number[] = []
      if (msg.id) idsToDelete.push(msg.id)
      if (pairedIndex >= 0 && messages.value[pairedIndex]?.id) {
        idsToDelete.push(messages.value[pairedIndex].id)
      }
      for (const id of idsToDelete) {
        try { await chatApi.deleteMessage(id) } catch (e) { /* ignore */ }
      }
      // 从前端删除
      if (pairedIndex >= 0) {
        const removeIndex = Math.min(index, pairedIndex)
        messages.value.splice(removeIndex, 2)
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
  height: calc(100vh - 70px);
  background: var(--color-bg-1);
  border-radius: 8px;
  overflow: hidden;
  min-width: 0;
}

/* 左侧边栏 */
.chat-sidebar {
  width: 220px;
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  background: var(--color-bg-2);
}

.sidebar-header {
  padding: 0px 0px 6px 0px;
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.sidebar-title {
  padding: 8px 5px 0px;
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
  min-width: 0;
  display: flex;
  flex-direction: column;
}

/* 配置栏 */
.config-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0px 0px 6px 20px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-1);
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.config-left {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
}

.config-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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
  overflow-x: hidden;
  padding: 20px;
  min-width: 0;
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
  width: 100%;
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
  min-width: 0;
  overflow: hidden;
}

.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.7;
  background: var(--color-bg-2);
  word-break: break-word;
  overflow-wrap: break-word;
  overflow: hidden;
}

.message-text :deep(p) { margin: 4px 0; }
.message-text :deep(h1),
.message-text :deep(h2),
.message-text :deep(h3) { margin: 8px 0 4px; font-weight: 600; }
.message-text :deep(ul),
.message-text :deep(ol) { padding-left: 20px; margin: 4px 0; }
.message-text :deep(li) { margin: 2px 0; }
.message-text :deep(blockquote) {
  border-left: 3px solid var(--color-primary);
  padding: 4px 12px;
  margin: 6px 0;
  color: var(--color-text-2);
  background: var(--color-bg-3);
  border-radius: 0 4px 4px 0;
}
.message-text :deep(pre) {
  background: var(--color-bg-3);
  padding: 10px 14px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 6px 0;
  font-size: 0.9em;
}
.message-text :deep(code) {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  background: var(--color-bg-3);
  padding: 2px 5px;
  border-radius: 3px;
  font-size: 0.9em;
}
.message-text :deep(pre code) { background: transparent; padding: 0; }
.message-text :deep(table) { width: 100%; border-collapse: collapse; margin: 6px 0; font-size: 0.95em; }
.message-text :deep(th),
.message-text :deep(td) { border: 1px solid var(--color-border); padding: 5px 8px; text-align: left; }
.message-text :deep(th) { background: var(--color-bg-3); font-weight: 600; }

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

/* 思考过程 */
.thinking-section {
  margin-bottom: 8px;
  border-left: 3px solid rgba(212, 145, 74, 0.25);
  background: var(--color-bg-2);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
}

.thinking-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}

.thinking-label {
  color: var(--color-text-3);
  font-weight: 500;
  font-size: 12px;
}

.thinking-toggle {
  color: var(--color-text-3);
  font-size: 11px;
}

.thinking-body {
  margin-top: 6px;
}

.thinking-step {
  padding: 4px 0;
  border-bottom: 1px solid var(--color-border);
  line-height: 1.4;
}

.thinking-step:last-child {
  border-bottom: none;
}

.thinking-stage-label {
  display: inline-block;
  font-size: 10px;
  color: #fff;
  padding: 2px 7px;
  border-radius: 3px;
  margin-right: 5px;
  vertical-align: middle;
  line-height: 1.5;
}

.thinking-step-content {
  color: var(--color-text-2);
  font-size: 12px;
  vertical-align: middle;
}

/* 用户反馈 */
.feedback-bar {
  display: flex;
  gap: 12px;
  margin-top: 8px;
  padding-top: 6px;
}

.feedback-btn {
  font-size: 18px;
  cursor: pointer;
  opacity: 0.5;
  transition: opacity 0.2s, transform 0.2s;
  user-select: none;
}

.feedback-btn:hover {
  opacity: 1;
  transform: scale(1.2);
}

.feedback-thanks {
  font-size: 11px;
  color: var(--color-text-3);
  margin-top: 6px;
}

/* 产物渲染（思维导图、图表等） */
.artifact-section {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.artifact-card {
  border: 1px solid var(--color-border-2);
  border-radius: 8px;
  overflow: hidden;
  background: var(--color-bg-1);
}

.artifact-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--color-fill-2);
  border-bottom: 1px solid var(--color-border-2);
}

.artifact-icon {
  font-size: 16px;
}

.artifact-label {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-1);
}

.artifact-body {
  padding: 12px;
  max-height: 500px;
  overflow-y: auto;
}

.artifact-markdown {
  font-size: 13px;
  line-height: 1.7;
}

.artifact-markdown :deep(h1),
.artifact-markdown :deep(h2),
.artifact-markdown :deep(h3) {
  margin-top: 10px;
  margin-bottom: 6px;
}

.artifact-markdown :deep(ul),
.artifact-markdown :deep(ol) {
  padding-left: 20px;
  margin: 4px 0;
}

.artifact-markdown :deep(li) {
  margin: 2px 0;
}

.artifact-raw {
  font-size: 12px;
  white-space: pre-wrap;
  color: var(--color-text-2);
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
  width: 100%;
}

.quote-text {
  flex: 1;
  font-size: 12px;
  color: var(--color-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 附件卡片 */
.attachments-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 20px 0;
}

.attachment-card {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  font-size: 12px;
  color: var(--color-text-2);
}

.attachment-name {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-size {
  color: var(--color-text-3);
  font-size: 11px;
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
  width: 100%;
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

/* 响应式：小屏幕隐藏侧边栏 */
@media (max-width: 768px) {
  .chat-sidebar {
    display: none;
  }

  .config-bar {
    padding: 0 0 6px 12px;
  }

  .chat-messages {
    padding: 12px;
  }

  .input-area {
    padding: 12px;
  }
}
</style>
