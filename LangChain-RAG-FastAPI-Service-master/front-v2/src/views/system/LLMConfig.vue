<template>
  <div class="llm-config">
    <div class="page-header">
      <div class="header-left">
        <h2>LLM 配置管理</h2>
        <a-tag>共 {{ configs.length }} 条</a-tag>
      </div>
      <div class="header-right">
        <a-button @click="$router.push('/system/prompt')">
          <template #icon><icon-file /></template>
          提示词管理
        </a-button>
        <a-button type="primary" @click="handleAdd">
          <template #icon><icon-plus /></template>
          新增配置
        </a-button>
      </div>
    </div>

    <a-card>
      <a-table :data="configs" :pagination="{ pageSize: 10 }">
        <template #columns>
          <a-table-column title="ID" data-index="id" :width="60" />
          <a-table-column title="配置名称" data-index="name" :width="120" />
          <a-table-column title="模型名称" data-index="model" :width="150" />
          <a-table-column title="API URL" data-index="apiUrl">
            <template #cell="{ record }">
              <a-tooltip :content="record.apiUrl">
                <span class="url-text">{{ record.apiUrl }}</span>
              </a-tooltip>
            </template>
          </a-table-column>
          <a-table-column title="系统提示词" data-index="systemPrompt" :width="100">
            <template #cell="{ record }">
              <a-tag v-if="record.systemPrompt" color="green">已设置</a-tag>
              <a-tag v-else color="gray">未设置</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="状态" data-index="enabled" :width="80">
            <template #cell="{ record }">
              <a-switch v-model="record.enabled" size="small" @change="handleToggleStatus(record)" />
            </template>
          </a-table-column>
          <a-table-column title="创建时间" data-index="createdAt" :width="160" />
          <a-table-column title="更新时间" data-index="updatedAt" :width="160" />
          <a-table-column title="操作" :width="120" fixed="right">
            <template #cell="{ record }">
              <a-space>
                <a-button type="text" size="small" @click="handleEdit(record)">
                  <template #icon><icon-edit /></template>
                  编辑
                </a-button>
                <a-button type="text" size="small" status="danger" @click="handleDelete(record)">
                  <template #icon><icon-delete /></template>
                  删除
                </a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:visible="modalVisible"
      :title="isEdit ? '编辑 LLM 配置' : '新增 LLM 配置'"
      :width="680"
      :mask-closable="false"
    >
      <a-form :model="form" layout="vertical">
        <!-- 基础连接配置 -->
        <div class="form-section">
          <div class="section-title">基础连接配置</div>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="配置名称" required>
                <a-input v-model="form.name" placeholder="例如：小米、OpenAI" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="供应商" required>
                <a-select v-model="form.provider" placeholder="选择供应商">
                  <a-option value="openai-compatible">OpenAI 兼容</a-option>
                  <a-option value="openai">OpenAI</a-option>
                  <a-option value="anthropic">Anthropic</a-option>
                  <a-option value="zhipu">智谱</a-option>
                  <a-option value="qwen">通义千问</a-option>
                  <a-option value="deepseek">DeepSeek</a-option>
                  <a-option value="ollama">Ollama</a-option>
                </a-select>
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="16">
            <a-col :span="16">
              <a-form-item label="模型名称" required>
                <a-input v-model="form.model" placeholder="例如：mimo-v2.5-pro">
                  <template #append>
                    <a-button @click="handleRefreshModels" :loading="refreshing">
                      <template #icon><icon-refresh /></template>
                    </a-button>
                  </template>
                </a-input>
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label=" ">
                <a-button type="outline" long @click="handleTestConnection" :loading="testing">
                  <template #icon><icon-link /></template>
                  测试连接
                </a-button>
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="API URL" required>
            <a-input v-model="form.apiUrl" placeholder="https://api.example.com/v1" />
          </a-form-item>
          <a-form-item label="API Key" :required="!isEdit">
            <a-input-password v-model="form.apiKey" :placeholder="isEdit ? '留空不修改' : '输入 API Key'" />
          </a-form-item>
        </div>

        <!-- 模型行为与对话预设 -->
        <div class="form-section">
          <div class="section-title">模型行为与对话预设</div>
          <a-form-item label="系统提示词 (System Prompt)">
            <a-textarea
              v-model="form.systemPrompt"
              placeholder="设置模型的默认 System Prompt（可选）"
              :auto-size="{ minRows: 3, maxRows: 6 }"
              :max-length="2000"
              show-word-limit
            />
          </a-form-item>
          <a-form-item label="上下文限制 (Token)">
            <a-input-number
              v-model="form.contextLimit"
              :min="1000"
              :max="1000000"
              :step="1000"
              style="width: 100%"
              placeholder="128000"
            />
            <div class="form-tip">该模型单次对话最多能接收的 Token 数量</div>
          </a-form-item>
        </div>

        <!-- 高级特性开关 -->
        <div class="form-section">
          <div class="section-title">高级特性开关</div>
          <a-row :gutter="24">
            <a-col :span="8">
              <a-form-item label="多模态 (Vision)">
                <a-switch v-model="form.vision" />
                <span class="switch-desc">{{ form.vision ? '支持图片识别' : '仅文本' }}</span>
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="流式输出 (Stream)">
                <a-switch v-model="form.stream" />
                <span class="switch-desc">{{ form.vision ? '实时输出' : '等待完成后输出' }}</span>
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="状态 (激活)">
                <a-switch v-model="form.enabled" />
                <span class="switch-desc">{{ form.enabled ? '已激活' : '已禁用' }}</span>
              </a-form-item>
            </a-col>
          </a-row>
        </div>

        <!-- 策略与安全管理 -->
        <div class="form-section">
          <div class="section-title">策略与安全管理</div>
          <a-row :gutter="24">
            <a-col :span="12">
              <a-form-item label="上下文摘要">
                <a-switch v-model="form.contextSummary" />
                <span class="switch-desc">超时自动压缩对话历史</span>
              </a-form-item>
              <div class="form-tip">开启后，当聊天记录太长时系统会自动压缩总结，节省 Token 消耗</div>
            </a-col>
            <a-col :span="12">
              <a-form-item label="人工审批">
                <a-switch v-model="form.humanApproval" />
                <span class="switch-desc">高风险操作需确认</span>
              </a-form-item>
              <div class="form-tip">开启后，AI 执行删除、发送等高风险操作前需人工确认</div>
            </a-col>
          </a-row>
        </div>
      </a-form>

      <template #footer>
        <a-button @click="modalVisible = false">取消</a-button>
        <a-button type="primary" @click="handleSave" :loading="saving">确定</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import {
  IconPlus,
  IconEdit,
  IconDelete,
  IconFile,
  IconRefresh,
  IconLink,
} from '@arco-design/web-vue/es/icon'

const modalVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const testing = ref(false)
const refreshing = ref(false)

const configs = ref<any[]>([
  {
    id: 1,
    name: '小米',
    provider: 'openai-compatible',
    model: 'mimo-v2.5-pro',
    apiUrl: 'https://api.xiaomimimo.com/v1',
    apiKey: '***',
    systemPrompt: '',
    contextLimit: 128000,
    vision: false,
    stream: true,
    contextSummary: true,
    humanApproval: false,
    enabled: true,
    createdAt: '2026/7/18 22:11:31',
    updatedAt: '2026/7/18 22:18:57',
  },
])

const form = reactive({
  name: '',
  provider: 'openai-compatible',
  model: '',
  apiUrl: '',
  apiKey: '',
  systemPrompt: '',
  contextLimit: 128000,
  vision: false,
  stream: true,
  enabled: true,
  contextSummary: true,
  humanApproval: false,
})

function handleAdd() {
  isEdit.value = false
  editId.value = null
  resetForm()
  modalVisible.value = true
}

function handleEdit(record: any) {
  isEdit.value = true
  editId.value = record.id
  Object.assign(form, {
    name: record.name,
    provider: record.provider || 'openai-compatible',
    model: record.model,
    apiUrl: record.apiUrl,
    apiKey: '', // 不回显密钥
    systemPrompt: record.systemPrompt,
    contextLimit: record.contextLimit || 128000,
    vision: record.vision || false,
    stream: record.stream !== false,
    enabled: record.enabled,
    contextSummary: record.contextSummary !== false,
    humanApproval: record.humanApproval || false,
  })
  modalVisible.value = true
}

function handleDelete(record: any) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除配置「${record.name}」吗？`,
    onOk: () => {
      configs.value = configs.value.filter(c => c.id !== record.id)
      Message.success('删除成功')
    },
  })
}

function handleToggleStatus(record: any) {
  Message.success(`已${record.enabled ? '激活' : '禁用'} ${record.name}`)
}

async function handleTestConnection() {
  if (!form.apiUrl || !form.apiKey) {
    Message.warning('请先填写 API URL 和 API Key')
    return
  }
  testing.value = true
  try {
    // TODO: 实现实际的测试连接逻辑
    await new Promise(resolve => setTimeout(resolve, 1500))
    Message.success('连接成功！')
  } catch (e) {
    Message.error('连接失败')
  } finally {
    testing.value = false
  }
}

async function handleRefreshModels() {
  if (!form.apiUrl || !form.apiKey) {
    Message.warning('请先填写 API URL 和 API Key')
    return
  }
  refreshing.value = true
  try {
    // TODO: 实现获取模型列表逻辑
    await new Promise(resolve => setTimeout(resolve, 1000))
    Message.success('模型列表已刷新')
  } catch (e) {
    Message.error('刷新失败')
  } finally {
    refreshing.value = false
  }
}

async function handleSave() {
  if (!form.name || !form.model || !form.apiUrl) {
    Message.warning('请填写必填项')
    return
  }
  if (!isEdit.value && !form.apiKey) {
    Message.warning('请输入 API Key')
    return
  }

  saving.value = true
  try {
    const now = new Date().toLocaleString('zh-CN')

    if (isEdit.value && editId.value) {
      const index = configs.value.findIndex(c => c.id === editId.value)
      if (index !== -1) {
        configs.value[index] = {
          ...configs.value[index],
          ...form,
          apiKey: form.apiKey ? '***' : configs.value[index].apiKey,
          updatedAt: now,
        }
        Message.success('更新成功')
      }
    } else {
      const newId = Math.max(...configs.value.map(c => c.id), 0) + 1
      configs.value.push({
        id: newId,
        ...form,
        apiKey: '***',
        createdAt: now,
        updatedAt: now,
      })
      Message.success('添加成功')
    }

    modalVisible.value = false
  } finally {
    saving.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    name: '',
    provider: 'openai-compatible',
    model: '',
    apiUrl: '',
    apiKey: '',
    systemPrompt: '',
    contextLimit: 128000,
    vision: false,
    stream: true,
    enabled: true,
    contextSummary: true,
    humanApproval: false,
  })
}
</script>

<style scoped>
.llm-config {
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-left h2 {
  margin: 0;
}

.header-right {
  display: flex;
  gap: 8px;
}

.url-text {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}

.form-section {
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border);
}

.form-section:last-child {
  border-bottom: none;
  margin-bottom: 0;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-1);
  margin-bottom: 16px;
}

.form-tip {
  font-size: 12px;
  color: var(--color-text-3);
  margin-top: 4px;
}

.switch-desc {
  margin-left: 8px;
  font-size: 12px;
  color: var(--color-text-3);
}
</style>
