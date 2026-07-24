<template>
  <div class="note-editor-layout">
    <!-- 左侧编辑器主区域 -->
    <div class="editor-main">
      <div class="editor-header">
        <a-button type="text" @click="$router.push('/notes')">
          <template #icon><icon-left /></template>
          返回
        </a-button>
        <div class="editor-actions">
          <a-space>
            <a-button v-if="!isNew" type="text" status="danger" @click="handleDelete">
              <template #icon><icon-delete /></template>
              删除
            </a-button>
            <a-button v-if="!isPreview" type="outline" @click="isPreview = true">
              <template #icon><icon-eye /></template>
              预览
            </a-button>
            <a-button v-else type="outline" @click="isPreview = false">
              <template #icon><icon-edit /></template>
              编辑
            </a-button>
            <a-button type="primary" @click="handleSave" :loading="saving">
              <template #icon><icon-save /></template>
              保存
            </a-button>
          </a-space>
        </div>
      </div>

      <div class="editor-body">
        <a-input
          v-model="form.title"
          placeholder="输入标题..."
          class="title-input"
          :border="false"
        />

        <!-- 编辑模式 -->
        <div v-if="!isPreview" class="editor-content">
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

        <!-- 预览模式 -->
        <div v-else class="preview-content markdown-body" v-html="renderedContent"></div>
      </div>
    </div>

    <!-- 右侧关联笔记侧边栏 -->
    <div class="sidebar-zone" v-if="!isNew">
      <!-- 折叠态 -->
      <div v-if="!sidebarVisible" class="sidebar-collapsed" @click="toggleSidebar">
        <icon-list />
        <span class="sidebar-hint">相关</span>
      </div>

      <!-- 展开态 -->
      <div v-else class="sidebar-panel">
        <div class="sidebar-header">
          <span class="sidebar-title">相关笔记</span>
          <a-button type="text" size="mini" @click="toggleSidebar">
            <template #icon><icon-close /></template>
          </a-button>
        </div>

        <div class="sidebar-body">
          <a-spin v-if="loadingRelated" style="display: flex; justify-content: center; padding: 40px 0;" />

          <template v-else-if="relatedItems.length > 0">
            <!-- 展开查看某篇关联笔记 -->
            <div v-if="expandedNote" class="related-detail">
              <div class="detail-back" @click="expandedNote = null">
                <icon-left style="font-size: 12px;" /> 返回列表
              </div>
              <h4 class="detail-title">{{ expandedNote.title }}</h4>
              <div class="detail-content markdown-body" v-html="renderMarkdown(expandedNote.content || expandedNote.contentPreview || '')"></div>
            </div>

            <!-- 列表视图 -->
            <div v-else class="related-list">
              <div
                v-for="item in relatedItems"
                :key="item.id"
                class="related-card"
                @click="expandRelatedNote(item)"
              >
                <div class="related-card-top">
                  <a-tag size="small" color="arcoblue">笔记</a-tag>
                  <span class="related-similarity">{{ (item.similarity * 100).toFixed(0) }}%</span>
                </div>
                <div class="related-card-title">{{ item.title }}</div>
                <div class="related-card-preview">{{ item.contentPreview }}</div>
              </div>
            </div>
          </template>

          <div v-else class="related-empty">暂无相关笔记</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import { IconLeft, IconSave, IconRobot, IconEdit, IconFile, IconDelete, IconEye, IconClose, IconList } from '@arco-design/web-vue/es/icon'
import { noteApi } from '@/api'
import { marked } from 'marked'

const route = useRoute()
const router = useRouter()
const saving = ref(false)
const isNew = ref(false)
const isPreview = ref(false)

const form = ref({
  title: '',
  content: '',
})

const renderedContent = computed(() => {
  return marked(form.value.content || '')
})

function renderMarkdown(text: string) {
  return marked(text || '')
}

// ========== 关联笔记侧边栏 ==========

const sidebarVisible = ref(false)
const relatedItems = ref<any[]>([])
const loadingRelated = ref(false)
const expandedNote = ref<any>(null)
let relatedRefreshTimer: ReturnType<typeof setTimeout> | null = null

function toggleSidebar() {
  sidebarVisible.value = !sidebarVisible.value
  if (sidebarVisible.value && relatedItems.value.length === 0) {
    fetchRelated()
  }
}

async function fetchRelated() {
  const id = route.params.id as string
  if (!id || id === 'new') return
  loadingRelated.value = true
  try {
    const res: any = await noteApi.getRelated(id)
    if (res.code === 200) {
      relatedItems.value = res.data || []
    }
  } catch (e) {
    // ignore
  } finally {
    loadingRelated.value = false
  }
}

function expandRelatedNote(item: any) {
  expandedNote.value = item
}

function scheduleRelatedRefresh() {
  const id = route.params.id as string
  if (!id || id === 'new' || !sidebarVisible.value) return
  if (relatedRefreshTimer) clearTimeout(relatedRefreshTimer)
  relatedRefreshTimer = setTimeout(() => {
    fetchRelated()
  }, 3000)
}

// 监听内容变化，防抖刷新关联笔记
watch(() => form.value.content, () => {
  scheduleRelatedRefresh()
})

// ========== 生命周期 ==========

onMounted(() => {
  const id = route.params.id as string
  if (id && id !== 'new') {
    fetchNote(id)
    isPreview.value = true
  } else {
    isNew.value = true
    isPreview.value = false
  }
})

onUnmounted(() => {
  if (relatedRefreshTimer) clearTimeout(relatedRefreshTimer)
})

// ========== 数据操作 ==========

async function fetchNote(id: string) {
  try {
    const res: any = await noteApi.get(id)
    if (res.code === 200 && res.data) {
      form.value = {
        title: res.data.title || '',
        content: res.data.content || '',
      }
    } else {
      Message.error('获取笔记失败')
      router.push('/notes')
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
      const res: any = await noteApi.create(form.value)
      if (res.code === 200 && res.data) {
        Message.success('创建成功')
        isNew.value = false
        isPreview.value = true
        router.replace(`/notes/${res.data.id}`)
      } else {
        Message.error(res.message || '创建失败')
      }
    } else {
      const res: any = await noteApi.update(id, form.value)
      if (res.code === 200) {
        Message.success('保存成功')
        isPreview.value = true
      } else {
        Message.error(res.message || '保存失败')
      }
    }
  } catch (e) {
    Message.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  const id = route.params.id as string
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除笔记「${form.value.title}」吗？`,
    onOk: async () => {
      try {
        const res: any = await noteApi.delete(id)
        if (res.code === 200) {
          Message.success('删除成功')
          router.push('/notes')
        } else {
          Message.error(res.message || '删除失败')
        }
      } catch (e) {
        Message.error('删除失败')
      }
    },
  })
}

async function handleAIAssist(action: string) {
  Message.info(`AI ${action} 功能开发中...`)
}
</script>

<style scoped>
.note-editor-layout {
  display: flex;
  gap: 0;
  min-height: calc(100vh - 92px);
}

.editor-main {
  flex: 1;
  min-width: 0;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  position: sticky;
  top: -16px;
  z-index: 10;
  background: var(--color-bg-2);
  padding: 12px 16px;
  margin: -16px -16px 16px;
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

/* 预览模式样式 */
.preview-content {
  padding: 16px;
  line-height: 1.8;
  color: var(--color-text-1);
  min-height: 400px;
}

.preview-content :deep(h1),
.preview-content :deep(h2),
.preview-content :deep(h3) {
  margin: 16px 0 8px;
  font-weight: 600;
}

.preview-content :deep(p) {
  margin: 0 0 12px;
}

.preview-content :deep(pre) {
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 14px;
  overflow-x: auto;
  font-size: 13px;
}

.preview-content :deep(code) {
  background: var(--color-bg-2);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 13px;
}

.preview-content :deep(pre code) {
  background: transparent;
  padding: 0;
}

.preview-content :deep(blockquote) {
  margin: 12px 0;
  padding: 8px 16px;
  border-left: 4px solid var(--color-primary);
  color: var(--color-text-2);
  background: var(--color-bg-2);
  border-radius: 0 4px 4px 0;
}

.preview-content :deep(ul),
.preview-content :deep(ol) {
  padding-left: 24px;
  margin: 8px 0;
}

.preview-content :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
}

.preview-content :deep(th),
.preview-content :deep(td) {
  border: 1px solid var(--color-border);
  padding: 8px 12px;
  font-size: 14px;
}

.preview-content :deep(th) {
  background: var(--color-bg-2);
  font-weight: 600;
}

.preview-content :deep(img) {
  max-width: 100%;
  border-radius: 4px;
}

/* ========== 关联笔记侧边栏 ========== */

.sidebar-zone {
  flex-shrink: 0;
  position: relative;
}

/* 折叠态 */
.sidebar-collapsed {
  width: 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 16px 0px;
  background: var(--color-bg-1);
  border-left: 1px solid var(--color-border);
  border-radius: 0 8px 8px 0;
  cursor: pointer;
  color: var(--color-text-3);
  transition: all 0.2s;
  position: sticky;
  top: 0;
  height: 100vh;
}

.sidebar-collapsed:hover {
  background: var(--color-bg-2);
  color: var(--color-primary);
}

.sidebar-hint {
  font-size: 11px;
  writing-mode: vertical-rl;
  letter-spacing: 2px;
}

/* 展开态 */
.sidebar-panel {
  width: 300px;
  background: var(--color-bg-1);
  border-left: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  height: calc(100vh - 92px);
  position: sticky;
  top: 0;
}

.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.sidebar-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-1);
}

.sidebar-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

/* 关联笔记卡片 */
.related-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.related-card {
  padding: 12px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.related-card:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-light-1);
}

.related-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.related-similarity {
  font-size: 12px;
  color: var(--color-text-3);
}

.related-card-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-1);
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.related-card-preview {
  font-size: 12px;
  color: var(--color-text-3);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 关联笔记详情 */
.related-detail {
  padding: 4px;
}

.detail-back {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
  margin-bottom: 12px;
}

.detail-back:hover {
  opacity: 0.8;
}

.detail-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-1);
}

.detail-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-2);
}

.detail-content :deep(h1),
.detail-content :deep(h2),
.detail-content :deep(h3) {
  margin: 12px 0 6px;
  font-size: 14px;
  font-weight: 600;
}

.detail-content :deep(p) {
  margin: 0 0 8px;
}

.detail-content :deep(pre) {
  background: var(--color-bg-2);
  border-radius: 4px;
  padding: 8px;
  font-size: 12px;
  overflow-x: auto;
}

.detail-content :deep(code) {
  background: var(--color-bg-2);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}

.detail-content :deep(pre code) {
  background: transparent;
  padding: 0;
}

.detail-content :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 10px;
  border-left: 3px solid var(--color-primary);
  color: var(--color-text-3);
}

.detail-content :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 8px 0;
}

.detail-content :deep(th),
.detail-content :deep(td) {
  border: 1px solid var(--color-border);
  padding: 4px 8px;
  font-size: 12px;
}

.detail-content :deep(img) {
  max-width: 100%;
}

.related-empty {
  text-align: center;
  padding: 40px 16px;
  color: var(--color-text-3);
  font-size: 13px;
}

/* 响应式：窄屏隐藏侧边栏 */
@media (max-width: 768px) {
  .sidebar-zone {
    display: none;
  }
}
</style>
