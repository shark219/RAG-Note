<template>
  <div class="daily-review">
    <div class="review-header">
      <h2>每日复习</h2>
      <a-tag color="blue" size="large">{{ today }}</a-tag>
    </div>

    <a-spin :loading="loading">
      <div v-if="reviews.length > 0" class="review-list">
        <div v-for="note in reviews" :key="note.id" class="review-card">
          <div class="review-card-header">
            <h3>{{ note.title }}</h3>
            <a-tag>{{ note.reviewCount || 0 }} 次复习</a-tag>
          </div>
          <div class="review-card-body">
            <p>{{ getPreview(note.content) }}</p>
          </div>
          <div class="review-card-footer">
            <a-space>
              <a-button type="primary" size="small" @click="handleReview(note)">
                <template #icon><icon-check /></template>
                已复习
              </a-button>
              <a-button size="small" @click="handleView(note)">
                <template #icon><icon-eye /></template>
                查看详情
              </a-button>
            </a-space>
          </div>
        </div>
      </div>
      <a-empty v-else description="今天没有需要复习的笔记" />
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { IconCheck, IconEye } from '@arco-design/web-vue/es/icon'
import { reviewApi } from '@/api'
import dayjs from 'dayjs'

const router = useRouter()
const loading = ref(false)
const reviews = ref<any[]>([])
const today = dayjs().format('YYYY年MM月DD日')

onMounted(() => {
  fetchReviews()
})

async function fetchReviews() {
  loading.value = true
  try {
    const res: any = await reviewApi.getToday()
    reviews.value = res || []
  } catch (e) {
    console.error('获取复习列表失败', e)
  } finally {
    loading.value = false
  }
}

async function handleReview(note: any) {
  try {
    await reviewApi.markDone(note.id)
    Message.success('已标记为已复习')
    fetchReviews()
  } catch (e) {
    Message.error('操作失败')
  }
}

function handleView(note: any) {
  router.push(`/notes/${note.id}`)
}

function getPreview(content: string) {
  if (!content) return ''
  return content.slice(0, 200) + (content.length > 200 ? '...' : '')
}
</script>

<style scoped>
.daily-review {
  max-width: 800px;
  margin: 0 auto;
}

.review-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.review-header h2 {
  margin: 0;
}

.review-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.review-card {
  background: var(--color-bg-1);
  border-radius: 8px;
  padding: 20px;
  border: 1px solid var(--color-border);
}

.review-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.review-card-header h3 {
  margin: 0;
  font-size: 18px;
}

.review-card-body {
  color: var(--color-text-2);
  line-height: 1.6;
  margin-bottom: 16px;
}

.review-card-footer {
  display: flex;
  justify-content: flex-end;
}
</style>
