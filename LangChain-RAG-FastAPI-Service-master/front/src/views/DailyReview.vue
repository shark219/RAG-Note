<template>
  <div class="daily-review-page">
    <van-nav-bar title="每日回顾" fixed />
    <div class="review-content">
      <van-loading v-if="loading" class="loading-center" />
      <van-empty v-else-if="reviews.length === 0 && !loading" description="今天没有需要回顾的笔记，太棒了！" />

      <div v-else class="review-list">
        <div class="review-header-bar">
          <span class="review-count">共 {{ reviews.length }} 篇待回顾</span>
          <span class="review-progress">{{ doneCount }} / {{ reviews.length }}</span>
        </div>

        <ReviewCard
          v-for="item in reviews"
          :key="item.noteId"
          :title="item.title"
          :question="'点击阅读笔记内容'"
          :tags="item.tags"
          :category="item.category"
          :review-count="item.reviewCount"
          :done="doneMap[item.noteId]"
          @click="handleCardClick(item)"
          @review-now="handleCardClick(item)"
          @done="handleDone(item)"
          @skip="handleSkip(item.noteId)"
        />
      </div>
    </div>

    <!-- 笔记阅读弹窗 -->
    <van-popup
      v-model:show="popupVisible"
      position="bottom"
      round
      :style="{ height: '85vh', padding: '0', borderRadius: '16px 16px 0 0' }"
      closeable
      @close="resetPopup"
    >
      <div class="read-popup">
        <div class="read-header">
          <h3 class="read-title">{{ currentNote.title }}</h3>
          <div class="read-meta" v-if="currentNote.category || (currentNote.tags && currentNote.tags.length)">
            <span v-if="currentNote.category" class="read-category">{{ categoryMap[currentNote.category] || currentNote.category }}</span>
            <span v-for="t in (currentNote.tags || [])" :key="t" class="read-tag">{{ t }}</span>
          </div>
        </div>

        <div v-if="noteLoading" class="read-loading">
          <van-loading type="spinner" size="24" />
          <p>加载笔记内容...</p>
        </div>

        <div v-else class="read-body">
          <div class="markdown-body" v-html="renderedContent"></div>
        </div>

        <div class="read-footer" v-if="!noteLoading">
          <van-button type="primary" block round size="large" @click="handleDone(currentNote)">
            阅读完成，标记已回顾
          </van-button>
        </div>
      </div>
    </van-popup>

    <TabBar />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { showToast } from 'vant'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { apiConfig } from '../config/api'
import { useUserStore } from '../store/user'
import ReviewCard from '../components/ReviewCard.vue'
import TabBar from '../components/TabBar.vue'

const userStore = useUserStore()
const loading = ref(false)
const reviews = ref([])
const doneMap = ref({})
const doneCount = ref(0)

/** 弹窗状态 */
const popupVisible = ref(false)
const noteLoading = ref(false)
const currentNote = ref({})
const noteContent = ref('')

const categoryMap = { work: '工作', study: '学习', life: '生活', project: '项目' }

/** 渲染 Markdown */
const renderedContent = computed(() => {
  if (!noteContent.value) return ''
  try {
    const html = marked(noteContent.value, { breaks: true, gfm: true, headerIds: false, mangle: false })
    return DOMPurify.sanitize(html)
  } catch {
    return noteContent.value
  }
})

/** 请求头 */
function getHeaders() {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${userStore.token}`,
  }
}

/** 点击卡片 —— 打开笔记阅读 */
async function handleCardClick(item) {
  if (doneMap.value[item.noteId]) return
  currentNote.value = item
  noteContent.value = ''
  popupVisible.value = true
  noteLoading.value = true

  try {
    const res = await fetch(apiConfig.endpoints.noteDetail(item.noteId), {
      headers: getHeaders(),
    })
    const json = await res.json()
    if (json.code === 200 && json.data) {
      noteContent.value = json.data.content || ''
    } else {
      showToast('加载笔记失败')
    }
  } catch (e) {
    showToast('网络错误')
  } finally {
    noteLoading.value = false
  }
}

/** 标记已回顾 */
async function handleDone(item) {
  if (!item || !item.noteId) return
  if (doneMap.value[item.noteId]) return
  try {
    const res = await fetch(apiConfig.endpoints.reviewDone(item.noteId), {
      method: 'POST',
      headers: getHeaders(),
    })
    const json = await res.json()
    if (json.code === 200) {
      doneMap.value[item.noteId] = true
      doneCount.value++
      popupVisible.value = false
      showToast('已标记回顾')
    } else {
      showToast(json.message || '操作失败')
    }
  } catch (e) {
    showToast('操作失败')
  }
}

/** 跳过 */
function handleSkip(noteId) {
  doneMap.value[noteId] = true
  doneCount.value++
}

/** 重置弹窗 */
function resetPopup() {
  currentNote.value = {}
  noteContent.value = ''
  noteLoading.value = false
}

/** 加载今日回顾列表 */
async function loadReviews() {
  loading.value = true
  try {
    const res = await fetch(apiConfig.endpoints.reviewToday, { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) {
      reviews.value = json.data.reviews || []
    }
  } catch (e) {
    showToast('加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadReviews()
})
</script>

<style scoped>
.daily-review-page {
  min-height: 100vh;
  background: var(--van-background, #f7f8fa);
}
.review-content {
  padding-top: 48px;
  padding-bottom: 60px;
}
.loading-center {
  display: flex;
  justify-content: center;
  padding: 80px 0;
}
.review-list {
  padding: 16px;
}
.review-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.review-count {
  font-size: 14px;
  color: #666;
}
.review-progress {
  font-size: 14px;
  color: var(--van-primary-color, #D4914A);
  font-weight: 600;
}

/* 阅读弹窗 */
.read-popup {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
}
.read-header {
  padding: 20px 20px 12px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.read-title {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
  color: #333;
}
.read-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.read-category {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  background: #f0f0f0;
  color: #666;
}
.read-tag {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(212, 145, 74, 0.1);
  color: #D4914A;
}
.read-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 0;
  color: #999;
}
.read-loading p {
  margin-top: 12px;
  font-size: 14px;
}
.read-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}
.read-footer {
  padding: 12px 20px 24px;
  border-top: 1px solid #f0f0f0;
  flex-shrink: 0;
}

/* Markdown 排版 */
.markdown-body :deep(p) {
  margin: 8px 0;
  line-height: 1.8;
  color: #333;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  margin: 16px 0 8px;
  font-weight: 600;
  color: #222;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}
.markdown-body :deep(li) {
  margin: 4px 0;
  line-height: 1.7;
}
.markdown-body :deep(blockquote) {
  border-left: 3px solid #D4914A;
  padding: 8px 12px;
  margin: 8px 0;
  color: #666;
  background: #fafafa;
  border-radius: 0 6px 6px 0;
}
.markdown-body :deep(pre) {
  background: #f5f5f5;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 10px 0;
  font-size: 0.9em;
}
.markdown-body :deep(code) {
  font-family: 'Consolas', 'Monaco', monospace;
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.9em;
}
.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #e8e8e8;
  padding: 8px 12px;
  text-align: left;
}
.markdown-body :deep(th) {
  background: #fafafa;
  font-weight: 600;
}
</style>
