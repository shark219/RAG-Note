<template>
  <div class="llm-config">
    <div class="page-header">
      <div class="header-left">
        <h2>LLM 配置管理</h2>
        <a-tag>共 {{ configs.length }} 条</a-tag>
      </div>
      <div class="header-right">
        <a-button type="primary" @click="handleAdd">
          <template #icon><icon-plus /></template>
          新增配置
        </a-button>
      </div>
    </div>

    <a-card>
      <a-table :data="configs" :pagination="{ pageSize: 10 }" :loading="loading">
        <template #columns>
          <a-table-column title="ID" data-index="id" :width="60" />
          <a-table-column title="配置名称" data-index="name" :width="120" />
          <a-table-column title="模型名称" data-index="model" :width="160" />
          <a-table-column title="API URL" data-index="apiUrl">
            <template #cell="{ record }">
              <a-tooltip :content="record.apiUrl">
                <span class="url-text">{{ record.apiUrl }}</span>
              </a-tooltip>
            </template>
          </a-table-column>
          <a-table-column title="当前激活" :width="90">
            <template #cell="{ record }">
              <a-tag v-if="record.isActive" color="green">使用中</a-tag>
              <a-tag v-else color="gray">未激活</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="创建时间" data-index="createdAt" :width="160" />
          <a-table-column title="更新时间" data-index="updatedAt" :width="160" />
          <a-table-column title="操作" :width="200" fixed="right">
            <template #cell="{ record }">
              <a-space>
                <a-button type="text" size="small" @click="handleEdit(record)">
                  <template #icon><icon-edit /></template>
                </a-button>
                <a-button v-if="!record.isActive" type="text" size="small" status="warning" @click="handleActivate(record)">
                  <template #icon><icon-check-circle /></template>
                </a-button>
                <a-button type="text" size="small" status="danger" @click="handleDelete(record)">
                  <template #icon><icon-delete /></template>
                </a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <a-modal
      v-model:visible="modalVisible"
      :title="isEdit ? '编辑 LLM 配置' : '新增 LLM 配置'"
      :width="560"
      :mask-closable="false"
    >
      <a-form :model="form" layout="vertical">
        <a-form-item label="配置名称" required>
          <a-input v-model="form.name" placeholder="例如：小米、DeepSeek" />
        </a-form-item>
        <a-row :gutter="16">
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
          <a-col :span="12">
            <a-form-item label="模型名称" required>
              <a-input v-model="form.model" placeholder="例如：deepseek-flash" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item v-if="form.provider === 'deepseek'" label="实际模型名（可选）">
          <a-input v-model="form.actualModel" placeholder="例如：deepseek-v4-flash" />
          <div class="form-tip">留空时，后端会自动把 deepseek-flash 映射成 deepseek-v4-flash。</div>
        </a-form-item>
        <a-form-item label="API URL" required>
          <a-input v-model="form.apiUrl" placeholder="https://api.example.com/v1" />
        </a-form-item>
        <a-form-item :label="isEdit ? 'API Key（留空不修改）' : 'API Key'" :required="!isEdit">
          <a-input-password v-model="form.apiKey" :placeholder="isEdit ? '留空不修改' : '输入 API Key'" />
        </a-form-item>
        <a-form-item>
          <a-button type="outline" :loading="testing" @click="handleTestConnection">
            <template #icon><icon-link /></template>
            测试连接
          </a-button>
          <span v-if="testResult" class="test-result" :class="testResult.success ? 'success' : 'fail'">
            {{ testResult.message }}
          </span>
        </a-form-item>
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
import { llmConfigApi } from '@/api'
import {
  IconPlus,
  IconEdit,
  IconDelete,
  IconLink,
  IconCheckCircle,
} from '@arco-design/web-vue/es/icon'

interface LlmConfig {
  id: number
  name: string
  provider: string
  model: string
  apiUrl: string
  actualModel?: string
  apiKey?: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

const configs = ref<LlmConfig[]>([])
const loading = ref(false)
const modalVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const testing = ref(false)
const testResult = ref<{ success: boolean; message: string } | null>(null)

const form = reactive({
  name: '',
  provider: 'openai-compatible',
  model: '',
  actualModel: '',
  apiUrl: '',
  apiKey: '',
})

onMounted(() => {
  loadConfigs()
})

async function loadConfigs() {
  loading.value = true
  try {
    const res: any = await llmConfigApi.list()
    configs.value = res.data || []
  } catch {
    Message.error('加载配置列表失败')
  } finally {
    loading.value = false
  }
}

function handleAdd() {
  isEdit.value = false
  editId.value = null
  testResult.value = null
  resetForm()
  modalVisible.value = true
}

function handleEdit(record: LlmConfig) {
  isEdit.value = true
  editId.value = record.id
  testResult.value = null
  Object.assign(form, {
    name: record.name,
    provider: record.provider,
    model: record.model,
    actualModel: record.actualModel || '',
    apiUrl: record.apiUrl,
    apiKey: '',
  })
  modalVisible.value = true
}

function handleDelete(record: LlmConfig) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除配置「${record.name}」吗？`,
    onOk: async () => {
      try {
        await llmConfigApi.delete(record.id)
        Message.success('删除成功')
        loadConfigs()
      } catch (e: any) {
        Message.error(e?.response?.data?.message || '删除失败')
      }
    },
  })
}

async function handleActivate(record: LlmConfig) {
  try {
    await llmConfigApi.activate(record.id)
    Message.success(`已激活「${record.name}」`)
    loadConfigs()
  } catch {
    Message.error('激活失败')
  }
}

async function handleTestConnection() {
  if (!form.apiUrl || !form.apiKey) {
    Message.warning('请先填写 API URL 和 API Key')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const res: any = await llmConfigApi.test({
      apiUrl: form.apiUrl,
      apiKey: form.apiKey,
      model: form.model,
      provider: form.provider,
    })
    testResult.value = res.data
    if (res.data?.success) {
      Message.success('连接成功')
    } else {
      Message.error(res.data?.message || '连接失败')
    }
  } catch {
    testResult.value = { success: false, message: '请求异常' }
    Message.error('测试请求异常')
  } finally {
    testing.value = false
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
    if (isEdit.value && editId.value) {
      await llmConfigApi.update(editId.value, { ...form })
      Message.success('更新成功')
    } else {
      await llmConfigApi.create({ ...form })
      Message.success('添加成功')
    }
    modalVisible.value = false
    loadConfigs()
  } catch (e: any) {
    Message.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    name: '',
    provider: 'openai-compatible',
    model: '',
    actualModel: '',
    apiUrl: '',
    apiKey: '',
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

.test-result {
  margin-left: 12px;
  font-size: 13px;
}

.test-result.success {
  color: var(--color-success-6);
}

.test-result.fail {
  color: var(--color-danger-6);
}
</style>
