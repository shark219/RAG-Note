<template>
  <div class="note-list">
    <div class="note-header">
      <a-input-search
        v-model="searchQuery"
        placeholder="搜索笔记..."
        style="width: 320px"
        @search="handleSearch"
        @press-enter="handleSearch"
        allow-clear
      />
      <a-button type="primary" @click="handleCreate">
        <template #icon><icon-plus /></template>
        新建笔记
      </a-button>
    </div>

    <!-- 分类筛选 -->
    <div class="category-bar">
      <a-tag
        v-for="c in categories"
        :key="c.key"
        :color="currentCategory === c.key ? 'arcoblue' : ''"
        :checkable="true"
        :checked="currentCategory === c.key"
        @check="filterByCategory(c.key)"
        size="large"
      >
        {{ c.label }}
      </a-tag>
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
            <a-dropdown @select="(e: any) => handleAction(String(e), note)">
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
            <div class="note-tags">
              <a-tag v-if="note.category" size="small" color="arcoblue">
                {{ categoryMap[note.category] || note.category }}
              </a-tag>
              <a-tag v-for="tag in (note.tags || []).slice(0, 3)" :key="tag" size="small">
                {{ tag }}
              </a-tag>
            </div>
            <span class="note-date">{{ formatRelativeTime(note.updatedAt) }}</span>
          </div>
        </div>
      </div>
      <a-empty v-else-if="!loading" description="暂无笔记" />
    </a-spin>

    <!-- 分页 -->
    <div v-if="totalCount > pageSize" class="pagination-bar">
      <a-pagination
        v-model:current="currentPage"
        :total="totalCount"
        :page-size="pageSize"
        show-total
        @change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import { IconPlus, IconMore } from '@arco-design/web-vue/es/icon'
import { noteApi } from '@/api'

const router = useRouter()
const loading = ref(false)
const searchQuery = ref('')
const notes = ref<any[]>([])
const currentPage = ref(1)
const pageSize = 20
const totalCount = ref(0)
const currentCategory = ref('all')
const isSearching = ref(false)

const categories = [
  { key: 'all', label: '全部' },
  { key: 'work', label: '工作' },
  { key: 'study', label: '学习' },
  { key: 'life', label: '生活' },
  { key: 'project', label: '项目' },
]

const categoryMap: Record<string, string> = {
  work: '工作',
  study: '学习',
  life: '生活',
  project: '项目',
}

onMounted(() => {
  fetchNotes()
})

async function fetchNotes() {
  loading.value = true
  try {
    const params: any = {
      page: currentPage.value,
      pageSize,
    }
    if (currentCategory.value !== 'all') {
      params.category = currentCategory.value
    }
    const res: any = await noteApi.list(params)
    if (res.code === 200 && res.data) {
      notes.value = res.data.notes || []
      totalCount.value = res.data.totalCount || 0
    } else {
      notes.value = []
    }
  } catch (e) {
    console.error('获取笔记列表失败', e)
    notes.value = []
  } finally {
    loading.value = false
  }
}

async function handleSearch() {
  const query = searchQuery.value.trim()
  if (!query) {
    isSearching.value = false
    currentPage.value = 1
    fetchNotes()
    return
  }
  loading.value = true
  isSearching.value = true
  try {
    const res: any = await noteApi.search(query)
    if (res.code === 200 && res.data) {
      // 按 id 去重（向量搜索可能返回重复结果）
      const raw = res.data.notes || []
      const seen = new Set<string>()
      notes.value = raw.filter((n: any) => {
        if (seen.has(n.id)) return false
        seen.add(n.id)
        return true
      })
      totalCount.value = notes.value.length
    } else {
      notes.value = []
    }
  } catch (e) {
    console.error('搜索失败', e)
    notes.value = []
  } finally {
    loading.value = false
  }
}

function filterByCategory(key: string) {
  currentCategory.value = key
  currentPage.value = 1
  searchQuery.value = ''
  isSearching.value = false
  fetchNotes()
}

function handlePageChange(page: number) {
  currentPage.value = page
  fetchNotes()
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
          const res: any = await noteApi.delete(note.id)
          if (res.code === 200) {
            Message.success('删除成功')
            fetchNotes()
          } else {
            Message.error(res.message || '删除失败')
          }
        } catch (e) {
          Message.error('删除失败')
        }
      },
    })
  }
}

function getPreview(content: string) {
  if (!content) return ''
  const text = content
    .replace(/#{1,6}\s*/g, '')
    .replace(/[*_`~>\[\]()!|-]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > 150 ? text.slice(0, 150) + '...' : text
}

function formatRelativeTime(dateStr: string) {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (hours < 24) return `${hours} 小时前`
  if (days < 30) return `${days} 天前`
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
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
  margin-bottom: 16px;
  gap: 12px;
  flex-wrap: wrap;
}

.note-header :deep(.arco-input-search) {
  flex: 1;
  min-width: 200px;
  max-width: 320px;
}

.category-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.note-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}

@media (max-width: 640px) {
  .note-header {
    flex-direction: column;
    align-items: stretch;
  }

  .note-header :deep(.arco-input-search) {
    max-width: none;
  }

  .note-grid {
    grid-template-columns: 1fr;
  }
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
  flex-wrap: wrap;
}

.note-date {
  flex-shrink: 0;
  margin-left: 8px;
}

.pagination-bar {
  display: flex;
  justify-content: center;
  margin-top: 24px;
  padding: 16px 0;
}

.danger {
  color: var(--color-danger);
}
</style>
