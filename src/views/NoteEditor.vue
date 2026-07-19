<template>
  <div class="note-editor">
    <div class="editor-header">
      <a-button type="text" @click="$router.push('/notes')">
        <template #icon><icon-left /></template>
        返回
      </a-button>
      <div class="editor-actions">
        <a-button @click="handleSave" :loading="saving">
          <template #icon><icon-save /></template>
          保存
        </a-button>
      </div>
    </div>

    <div class="editor-body">
      <a-input
        v-model="form.title"
        placeholder="输入标题..."
        class="title-input"
        :border="false"
      />
      <div class="editor-content">
        <div class="editor-toolbar">
          <a-space>
            <a-tooltip content="AI 续写">
              <a-button type="text" size="small" @click="handleAIAssist('continue')">
                <icon-robot />
              </a-button>
            </a-tooltip>
            <a-tooltip content="AI 优化">
              <a-button type="text" size="small" @click="handleAIAssist('improve')">
                <icon-edit />
              </a-button>
            </a-tooltip>
            <a-tooltip content="AI 总结">
              <a-button type="text" size="small" @click="handleAIAssist('summarize')">
                <icon-file />
              </a-button>
            </a-tooltip>
          </a-space>
        </div>
        <a-textarea
          v-model="form.content"
          placeholder="开始写作..."
          :auto-size="{ minRows: 20 }"
          class="content-textarea"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { IconLeft, IconSave, IconRobot, IconEdit, IconFile } from '@arco-design/web-vue/es/icon'
import { noteApi } from '@/api'

const route = useRoute()
const router = useRouter()
const saving = ref(false)
const isNew = ref(false)

const form = ref({
  title: '',
  content: '',
})

onMounted(() => {
  const id = route.params.id as string
  if (id && id !== 'new') {
    fetchNote(id)
  } else {
    isNew.value = true
  }
})

async function fetchNote(id: string) {
  try {
    const res: any = await noteApi.get(id)
    form.value = {
      title: res.title || '',
      content: res.content || '',
    }
  } catch (e) {
    Message.error('获取笔记失败')
    router.push('/notes')
  }
}

async function handleSave() {
  if (!form.value.title.trim()) {
    Message.warning('请输入标题')
    return
  }
  saving.value = true
  try {
    const id = route.params.id as string
    if (isNew.value) {
      await noteApi.create(form.value)
      Message.success('创建成功')
    } else {
      await noteApi.update(id, form.value)
      Message.success('保存成功')
    }
    router.push('/notes')
  } catch (e) {
    Message.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function handleAIAssist(action: string) {
  Message.info(`AI ${action} 功能开发中...`)
}
</script>

<style scoped>
.note-editor {
  max-width: 900px;
  margin: 0 auto;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.editor-body {
  background: var(--color-bg-1);
  border-radius: 8px;
  padding: 24px;
}

.title-input {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 16px;
}

.editor-content {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
}

.editor-toolbar {
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-2);
}

.content-textarea {
  border: none !important;
  border-radius: 0 !important;
}

.content-textarea :deep(textarea) {
  border: none !important;
  box-shadow: none !important;
}
</style>
