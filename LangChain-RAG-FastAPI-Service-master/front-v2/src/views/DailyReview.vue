<template>
  <div class="daily-review">
    <!-- 顶部统计 -->
    <div class="review-header">
      <h2>每日复习</h2>
      <a-space>
        <a-tag color="blue" size="large">{{ today }}</a-tag>
        <a-tag :color="progressColor" size="large">
          已完成 {{ completedCount }} / {{ totalCount }}
        </a-tag>
      </a-space>
    </div>

    <!-- 进度条 -->
    <div v-if="totalCount > 0" class="progress-bar">
      <a-progress
        :percent="progressPercent"
        :color="progressColor"
        :show-text="false"
        size="small"
      />
    </div>

    <a-spin :loading="loading">
      <div v-if="reviews.length > 0" class="review-list">
        <div v-for="(note, index) in reviews" :key="note.noteId" class="review-card">
          <div class="review-card-header">
            <div class="review-card-title">
              <a-tag color="arcoblue" size="small">{{ index + 1 }}/{{ totalCount }}</a-tag>
              <h3>{{ note.title }}</h3>
            </div>
            <a-tag>{{ note.reviewCount || 0 }} 次复习</a-tag>
          </div>
          <div class="review-card-body">
            <p>{{ note.contentPreview || '暂无预览' }}</p>
          </div>
          <div class="review-card-footer">
            <a-space>
              <a-button type="primary" size="small" @click="openQuiz(note)">
                <template #icon><icon-play-arrow /></template>
                开始答题
              </a-button>
              <a-button size="small" status="success" @click="handleReview(note)">
                <template #icon><icon-check /></template>
                已复习
              </a-button>
              <a-button size="small" @click="handleView(note)">
                <template #icon><icon-eye /></template>
                查看笔记
              </a-button>
              <a-button size="small" status="warning" @click="handleSkip(note)">
                <template #icon><icon-forward /></template>
                跳过
              </a-button>
            </a-space>
          </div>
        </div>
      </div>
      <div v-else-if="!loading" class="empty-state">
        <a-empty description="今天没有需要复习的笔记" />
        <p v-if="completedCount > 0" class="empty-hint">
          今日已完成 {{ completedCount }} 篇复习，明天再来吧！
        </p>
      </div>
    </a-spin>

    <!-- 选择题弹窗 -->
    <a-modal
      v-model:visible="quizVisible"
      :title="quizTitle"
      :width="560"
      :mask-closable="false"
      :footer="false"
      @close="closeQuiz"
    >
      <div v-if="quizLoading" class="quiz-loading">
        <a-spin />
        <p>正在生成题目...</p>
      </div>
      <div v-else-if="quizData" class="quiz-content">
        <!-- 题目 -->
        <div class="quiz-question">{{ quizData.question }}</div>

        <!-- 选项列表 -->
        <div class="quiz-choices">
          <div
            v-for="(choice, idx) in quizData.choices"
            :key="idx"
            class="quiz-choice"
            :class="getChoiceClass(choice, idx)"
            @click="selectAnswer(choice, idx)"
          >
            <span class="quiz-choice-label">{{ choiceLabels[idx] }}</span>
            <span class="quiz-choice-text">{{ choice }}</span>
            <icon-check-circle-fill v-if="isCorrect(choice)" class="choice-icon choice-icon--correct" />
            <icon-close-circle-fill v-else-if="isWrong(choice)" class="choice-icon choice-icon--wrong" />
          </div>
        </div>

        <!-- 反馈区域 -->
        <div v-if="answered" class="quiz-feedback">
          <a-alert
            :type="isAnswerCorrect ? 'success' : 'error'"
            :title="isAnswerCorrect ? '回答正确！' : '回答错误'"
          >
            <template #default>
              <p v-if="isAnswerCorrect">太棒了！继续加油！</p>
              <p v-else>正确答案是：<strong>{{ quizData.answer }}</strong></p>
            </template>
          </a-alert>
        </div>

        <!-- 操作按钮 -->
        <div class="quiz-actions">
          <a-space>
            <a-button v-if="answered" type="primary" @click="markAndNext">
              标记已复习，下一题
            </a-button>
            <a-button v-if="!answered" @click="showAnswer">
              查看答案
            </a-button>
            <a-button @click="closeQuiz">关闭</a-button>
          </a-space>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import {
  IconCheck, IconEye, IconPlayArrow, IconForward,
  IconCheckCircleFill, IconCloseCircleFill,
} from '@arco-design/web-vue/es/icon'
import { reviewApi } from '@/api'
import dayjs from 'dayjs'

const router = useRouter()
const loading = ref(false)
const reviews = ref<any[]>([])
const completedCount = ref(0)
const today = dayjs().format('YYYY年MM月DD日')

// 计算属性
const totalCount = computed(() => reviews.value.length + completedCount.value)
const progressPercent = computed(() => {
  if (totalCount.value === 0) return 0
  return completedCount.value / totalCount.value
})
const progressColor = computed(() => {
  if (progressPercent.value >= 1) return '#00b42a'
  if (progressPercent.value >= 0.5) return '#165dff'
  return '#f77234'
})

// ========== 选择题状态 ==========
const quizVisible = ref(false)
const quizLoading = ref(false)
const quizData = ref<any>(null)
const quizNote = ref<any>(null)
const selectedIdx = ref<number>(-1)
const answered = ref(false)
const isAnswerCorrect = ref(false)
const choiceLabels = ['A', 'B', 'C', 'D']

const quizTitle = computed(() => {
  return quizNote.value ? `复习 - ${quizNote.value.title}` : '复习答题'
})

// ========== 生命周期 ==========
onMounted(() => {
  fetchReviews()
})

// ========== 获取复习列表 ==========
async function fetchReviews() {
  loading.value = true
  try {
    const res: any = await reviewApi.getToday()
    const data = res?.data || res
    reviews.value = data?.reviews || []
  } catch (e) {
    console.error('获取复习列表失败', e)
    Message.error('获取复习列表失败')
  } finally {
    loading.value = false
  }
}

// ========== 答题逻辑 ==========
async function openQuiz(note: any) {
  quizNote.value = note
  quizVisible.value = true
  quizLoading.value = true
  quizData.value = null
  selectedIdx.value = -1
  answered.value = false
  isAnswerCorrect.value = false

  try {
    const res: any = await reviewApi.getQuestion(note.noteId)
    const data = res?.data || res
    quizData.value = data
  } catch (e: any) {
    console.error('获取题目失败', e)
    Message.error('题目生成失败，请重试')
    quizVisible.value = false
  } finally {
    quizLoading.value = false
  }
}

function selectAnswer(choice: string, idx: number) {
  if (answered.value) return
  selectedIdx.value = idx
  answered.value = true
  isAnswerCorrect.value = choice === quizData.value.answer
}

function showAnswer() {
  answered.value = true
  selectedIdx.value = -1
  isAnswerCorrect.value = false
}

function isCorrect(choice: string) {
  return answered.value && choice === quizData.value?.answer
}

function isWrong(choice: string) {
  return answered.value && selectedIdx.value >= 0 &&
    choice === quizData.value?.choices[selectedIdx.value] &&
    choice !== quizData.value?.answer
}

function getChoiceClass(choice: string, idx: number) {
  if (!answered.value) return ''
  if (choice === quizData.value?.answer) return 'choice--correct'
  if (idx === selectedIdx.value && choice !== quizData.value?.answer) return 'choice--wrong'
  return 'choice--dimmed'
}

async function markAndNext() {
  if (!quizNote.value) return
  await handleReview(quizNote.value)
  closeQuiz()
}

function closeQuiz() {
  quizVisible.value = false
  quizData.value = null
  quizNote.value = null
}

// ========== 复习操作 ==========
async function handleReview(note: any) {
  try {
    await reviewApi.markDone(note.noteId)
    Message.success('已标记为已复习')
    completedCount.value++
    reviews.value = reviews.value.filter(r => r.noteId !== note.noteId)
  } catch (e) {
    Message.error('操作失败')
  }
}

function handleView(note: any) {
  router.push(`/notes/${note.noteId}`)
}

function handleSkip(note: any) {
  reviews.value = reviews.value.filter(r => r.noteId !== note.noteId)
  Message.info(`已跳过「${note.title}」`)
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
  margin-bottom: 16px;
}

.review-header h2 {
  margin: 0;
}

.progress-bar {
  margin-bottom: 20px;
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

.review-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.review-card-title h3 {
  margin: 0;
  font-size: 16px;
}

.review-card-body {
  color: var(--color-text-2);
  line-height: 1.6;
  margin-bottom: 16px;
  font-size: 14px;
}

.review-card-body p {
  margin: 0;
}

.review-card-footer {
  display: flex;
  justify-content: flex-end;
}

.empty-state {
  text-align: center;
  padding: 40px 0;
}

.empty-hint {
  color: var(--color-text-3);
  margin-top: 12px;
  font-size: 14px;
}

/* ========== 选择题弹窗 ========== */
.quiz-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
  gap: 16px;
}

.quiz-loading p {
  color: var(--color-text-3);
  margin: 0;
}

.quiz-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.quiz-question {
  font-size: 16px;
  font-weight: 500;
  line-height: 1.6;
  color: var(--color-text-1);
  padding: 16px;
  background: var(--color-fill-2);
  border-radius: 8px;
}

.quiz-choices {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.quiz-choice {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 2px solid var(--color-border);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.quiz-choice:hover:not(.choice--correct):not(.choice--wrong):not(.choice--dimmed) {
  border-color: var(--color-primary);
  background: var(--color-primary-light-1);
}

.quiz-choice-label {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-fill-3);
  border-radius: 50%;
  font-weight: 600;
  font-size: 14px;
  flex-shrink: 0;
}

.quiz-choice-text {
  flex: 1;
  font-size: 14px;
  line-height: 1.5;
}

.choice--correct {
  border-color: #00b42a;
  background: #e8ffea;
}

.choice--correct .quiz-choice-label {
  background: #00b42a;
  color: #fff;
}

.choice--wrong {
  border-color: #f53f3f;
  background: #ffece8;
}

.choice--wrong .quiz-choice-label {
  background: #f53f3f;
  color: #fff;
}

.choice--dimmed {
  opacity: 0.5;
  cursor: default;
}

.choice-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.choice-icon--correct {
  color: #00b42a;
}

.choice-icon--wrong {
  color: #f53f3f;
}

.quiz-feedback {
  margin-top: 4px;
}

.quiz-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
