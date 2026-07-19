<template>
  <div class="prompt-manage">
    <div class="page-header">
      <h2>提示词管理</h2>
      <a-button type="primary" @click="handleAdd">
        <template #icon><icon-plus /></template>
        新增提示词
      </a-button>
    </div>

    <a-row :gutter="16">
      <a-col :span="8" v-for="(prompt, index) in prompts" :key="index">
        <a-card class="prompt-card" :class="{ active: prompt.isDefault }">
          <template #title>
            <div class="card-title">
              <span>{{ prompt.name }}</span>
              <a-tag v-if="prompt.isDefault" color="blue">默认</a-tag>
            </div>
          </template>
          <template #extra>
            <a-space>
              <a-button type="text" size="mini" @click="handleSetDefault(index)">
                <template #icon><icon-star /></template>
              </a-button>
              <a-button type="text" size="mini" @click="handleEdit(index)">
                <template #icon><icon-edit /></template>
              </a-button>
              <a-button type="text" size="mini" status="danger" @click="handleDelete(index)">
                <template #icon><icon-delete /></template>
              </a-button>
            </a-space>
          </template>
          <div class="prompt-content">{{ prompt.content }}</div>
          <div class="prompt-meta">
            <span>类型：{{ prompt.type || '通用' }}</span>
            <span>{{ prompt.updatedAt }}</span>
          </div>
        </a-card>
      </a-col>
    </a-row>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:visible="modalVisible"
      :title="isEdit ? '编辑提示词' : '新增提示词'"
      :width="600"
      @before-ok="handleSave"
    >
      <a-form :model="form" layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model="form.name" placeholder="输入提示词名称" />
        </a-form-item>
        <a-form-item label="类型">
          <a-select v-model="form.type" placeholder="选择类型">
            <a-option value="通用">通用</a-option>
            <a-option value="笔记">笔记助手</a-option>
            <a-option value="知识库">知识库问答</a-option>
            <a-option value="创意">创意写作</a-option>
            <a-option value="代码">代码助手</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="内容" required>
          <a-textarea
            v-model="form.content"
            placeholder="输入提示词内容"
            :auto-size="{ minRows: 6, maxRows: 12 }"
          />
        </a-form-item>
        <a-form-item label="设为默认">
          <a-switch v-model="form.isDefault" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { IconPlus, IconEdit, IconDelete, IconStar } from '@arco-design/web-vue/es/icon'

const modalVisible = ref(false)
const isEdit = ref(false)
const editIndex = ref(-1)

const prompts = ref([
  {
    name: '默认通用提示词',
    type: '通用',
    content: '你是一个智能笔记助手，可以帮助用户管理笔记、搜索知识库、安排复习计划。请用中文回答，保持简洁专业。',
    isDefault: true,
    updatedAt: '2026-07-18',
  },
  {
    name: '笔记助手',
    type: '笔记',
    content: '你是一个专业的笔记助手，擅长整理、总结和扩展笔记内容。可以帮助用户梳理思路、补充细节、纠正错误。',
    isDefault: false,
    updatedAt: '2026-07-18',
  },
  {
    name: '知识库问答',
    type: '知识库',
    content: '基于提供的知识库内容，准确回答用户问题。如果知识库中没有相关内容，请明确说明，不要编造信息。',
    isDefault: false,
    updatedAt: '2026-07-18',
  },
])

const form = reactive({
  name: '',
  type: '通用',
  content: '',
  isDefault: false,
})

function handleAdd() {
  isEdit.value = false
  editIndex.value = -1
  resetForm()
  modalVisible.value = true
}

function handleEdit(index: number) {
  isEdit.value = true
  editIndex.value = index
  const prompt = prompts.value[index]
  Object.assign(form, {
    name: prompt.name,
    type: prompt.type,
    content: prompt.content,
    isDefault: prompt.isDefault,
  })
  modalVisible.value = true
}

function handleDelete(index: number) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除提示词「${prompts.value[index].name}」吗？`,
    onOk: () => {
      prompts.value.splice(index, 1)
      Message.success('删除成功')
    },
  })
}

function handleSetDefault(index: number) {
  prompts.value.forEach((p, i) => {
    p.isDefault = i === index
  })
  Message.success('已设为默认')
}

function handleSave() {
  if (!form.name || !form.content) {
    Message.warning('请填写必填项')
    return false
  }

  const now = new Date().toISOString().split('T')[0]

  if (isEdit.value && editIndex.value >= 0) {
    prompts.value[editIndex.value] = {
      ...prompts.value[editIndex.value],
      ...form,
      updatedAt: now,
    }
    Message.success('更新成功')
  } else {
    prompts.value.push({
      ...form,
      updatedAt: now,
    })
    Message.success('添加成功')
  }

  return true
}

function resetForm() {
  Object.assign(form, {
    name: '',
    type: '通用',
    content: '',
    isDefault: false,
  })
}
</script>

<style scoped>
.prompt-manage {
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

.prompt-card {
  margin-bottom: 16px;
  transition: all 0.2s;
}

.prompt-card.active {
  border-color: var(--color-primary);
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.prompt-content {
  color: var(--color-text-2);
  font-size: 13px;
  line-height: 1.6;
  max-height: 100px;
  overflow: hidden;
  margin-bottom: 12px;
}

.prompt-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--color-text-3);
}
</style>
