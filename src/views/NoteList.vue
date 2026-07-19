<template>
  <div class="note-list">
    <div class="note-header">
      <a-input-search
        v-model="searchQuery"
        placeholder="搜索笔记..."
        style="width: 320px"
        @search="handleSearch"
      />
      <a-button type="primary" @click="handleCreate">
        <template #icon><icon-plus /></template>
        新建笔记
      </a-button>
    </div>

    <a-spin :loading="loading" style="width: 100%">
      <div v-if="notes.length" class="note-grid">
        <div
          v-for="note in notes"
          :key="note.id"
          class="note-card"
          @click="$router.push(`/notes/${note.id}`)"
        >
          <div class="note-card-header">
            <h3>{{ note.title }}</h3>
            <a-dropdown @select="(e: string) => handleAction(e, note)">
              <a-button type="text" size="small">
                <icon-more />
              </a-button>
              <template #content>
                <a-doption value="edit">编辑</a-doption>
                <a-doption value="delete" class="danger">删除</a-doption>
              </template>
            </a-dropdown>
          </div>
          <p class="note-preview">{{ getPreview(note.content) }}</p>
          <div class="note-meta">
            <span>{{ formatDate(note.updatedAt) }}</span>
            <div class="note-tags">
              <a-tag v-for="tag in (note.tags || []).slice(0, 3)" :key="tag" size="small">
                {{ tag }}
              </a-tag>
            </div>
          </div>
        </div>
      </div>
      <a-empty v-else description="暂无笔记" />
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import { IconPlus, IconMore } from '@arco-design/web-vue/es/icon'
import { noteApi } from '@/api'
import dayjs from 'dayjs'

const router = useRouter()
const loading = ref(false)
const searchQuery = ref('')
const notes = ref<any[]>([])

onMounted(() => {
  fetchNotes()
})

async function fetchNotes() {
  loading.value = true
  try {
    const res: any = await noteApi.list()
    notes.value = res || []
  } catch (e) {
    console.error('获取笔记列表失败', e)
  } finally {
    loading.value = false
  }
}

async function handleSearch(query: string) {
  if (!query.trim()) {
    fetchNotes()
    return
  }
  loading.value = true
  try {
    const res: any = await noteApi.search({ query })
    notes.value = res || []
  } catch (e) {
    console.error('搜索失败', e)
  } finally {
    loading.value = false
  }
}

function handleCreate() {
  router.push('/notes/new')
}

async function handleAction(action: string, note: any) {
  if (action === 'edit') {
    router.push(`/notes/${note.id}`)
  } else if (action === 'delete') {
    Modal.confirm({
      title: '确认删除',
      content: `确定要删除笔记「${note.title}」吗？`,
      onOk: async () => {
        try {
          await noteApi.delete(note.id)
          Message.success('删除成功')
          fetchNotes()
        } catch (e) {
          Message.error('删除失败')
        }
      },
    })
  }
}

function getPreview(content: string) {
  if (!content) return ''
  return content.slice(0, 150) + (content.length > 150 ? '...' : '')
}

function formatDate(date: string) {
  return dayjs(date).format('MM-DD HH:mm')
}
</script>

<style scoped>
.note-list {
  max-width: 1200px;
  margin: 0 auto;
}

.note-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.note-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.note-card {
  background: var(--color-bg-1);
  border-radius: 8px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid var(--color-border);
}

.note-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.note-card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.note-card-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--color-text-1);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.note-preview {
  color: var(--color-text-3);
  font-size: 13px;
  line-height: 1.6;
  margin: 8px 0;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.note-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--color-text-3);
}

.note-tags {
  display: flex;
  gap: 4px;
}

.danger {
  color: var(--color-danger);
}
</style>
