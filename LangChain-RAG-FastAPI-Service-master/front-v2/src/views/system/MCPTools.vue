<template>
  <div class="mcp-tools">
    <div class="page-header">
      <h2>MCP 工具管理</h2>
      <a-button type="primary" @click="handleAdd">
        <template #icon><icon-plus /></template>
        新增工具
      </a-button>
    </div>

    <a-card>
      <a-table :data="tools" :pagination="{ pageSize: 10 }">
        <template #columns>
          <a-table-column title="ID" data-index="id" :width="60" />
          <a-table-column title="工具名称" data-index="name" :width="150" />
          <a-table-column title="描述" data-index="description" :ellipsis="true" />
          <a-table-column title="服务地址" data-index="url" :width="200">
            <template #cell="{ record }">
              <span class="url-text">{{ record.url }}</span>
            </template>
          </a-table-column>
          <a-table-column title="类型" data-index="type" :width="100">
            <template #cell="{ record }">
              <a-tag>{{ record.type }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="状态" data-index="enabled" :width="80">
            <template #cell="{ record }">
              <a-switch v-model="record.enabled" size="small" />
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="150" fixed="right">
            <template #cell="{ record }">
              <a-space>
                <a-button type="text" size="small" @click="handlePing(record)">
                  测试
                </a-button>
                <a-button type="text" size="small" @click="handleEdit(record)">
                  编辑
                </a-button>
                <a-button type="text" size="small" status="danger" @click="handleDelete(record)">
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
      :title="isEdit ? '编辑 MCP 工具' : '新增 MCP 工具'"
      :width="600"
      @before-ok="handleSave"
    >
      <a-form :model="form" layout="vertical">
        <a-form-item label="工具名称" required>
          <a-input v-model="form.name" placeholder="输入工具名称" />
        </a-form-item>
        <a-form-item label="描述">
          <a-input v-model="form.description" placeholder="输入工具描述" />
        </a-form-item>
        <a-form-item label="服务地址" required>
          <a-input v-model="form.url" placeholder="http://localhost:8080/mcp" />
        </a-form-item>
        <a-form-item label="类型">
          <a-select v-model="form.type" placeholder="选择类型">
            <a-option value="stdio">stdio</a-option>
            <a-option value="sse">sse</a-option>
            <a-option value="streamable">streamable</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="API Key">
          <a-input-password v-model="form.apiKey" placeholder="输入 API Key（可选）" />
        </a-form-item>
        <a-form-item label="状态">
          <a-switch v-model="form.enabled" />
          <span style="margin-left: 8px">{{ form.enabled ? '已激活' : '已禁用' }}</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { IconPlus } from '@arco-design/web-vue/es/icon'

const modalVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)

const tools = ref<any[]>([
  {
    id: 1,
    name: 'Playwright Browser',
    description: '浏览器自动化工具，支持网页操作',
    url: 'http://localhost:3001',
    type: 'sse',
    apiKey: '',
    enabled: true,
  },
  {
    id: 2,
    name: 'File Manager',
    description: '文件管理工具，支持读写文件',
    url: 'http://localhost:3002',
    type: 'stdio',
    apiKey: '',
    enabled: true,
  },
])

const form = reactive({
  name: '',
  description: '',
  url: '',
  type: 'sse',
  apiKey: '',
  enabled: true,
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
    description: record.description,
    url: record.url,
    type: record.type,
    apiKey: record.apiKey,
    enabled: record.enabled,
  })
  modalVisible.value = true
}

function handleDelete(record: any) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除工具「${record.name}」吗？`,
    onOk: () => {
      tools.value = tools.value.filter(t => t.id !== record.id)
      Message.success('删除成功')
    },
  })
}

async function handlePing(record: any) {
  Message.info(`正在测试 ${record.name}...`)
  // TODO: 实现实际的 ping 测试
  setTimeout(() => {
    Message.success(`${record.name} 连接正常`)
  }, 1000)
}

function handleSave() {
  if (!form.name || !form.url) {
    Message.warning('请填写必填项')
    return false
  }

  if (isEdit.value && editId.value) {
    const index = tools.value.findIndex(t => t.id === editId.value)
    if (index !== -1) {
      tools.value[index] = { ...tools.value[index], ...form }
      Message.success('更新成功')
    }
  } else {
    const newId = Math.max(...tools.value.map(t => t.id), 0) + 1
    tools.value.push({ id: newId, ...form })
    Message.success('添加成功')
  }

  return true
}

function resetForm() {
  Object.assign(form, {
    name: '',
    description: '',
    url: '',
    type: 'sse',
    apiKey: '',
    enabled: true,
  })
}
</script>

<style scoped>
.mcp-tools {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
}

.url-text {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}
</style>
