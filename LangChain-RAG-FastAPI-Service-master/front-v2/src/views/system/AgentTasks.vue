<template>
  <div class="agent-tasks-page">
    <div class="page-header">
      <div>
        <h2>Agent 任务</h2>
        <div class="page-desc">查看任务状态、恢复执行、追踪时间线</div>
      </div>
      <a-button @click="loadTasks" :loading="loading">
        <template #icon><icon-refresh /></template>
        刷新
      </a-button>
    </div>

    <a-row :gutter="16">
      <a-col :xs="24" :lg="10">
        <a-card title="任务列表" class="section-card">
          <div v-if="tasks.length" class="task-list">
            <div
              v-for="task in tasks"
              :key="task.taskId"
              class="task-item"
              :class="{ active: selectedTaskId === task.taskId }"
              @click="selectTask(task.taskId)"
            >
              <div class="task-item-header">
                <a-tag :color="statusColor(task.status)">{{ task.status }}</a-tag>
                <a-tag v-if="task.resumable" color="orange">可恢复</a-tag>
              </div>
              <div class="task-query">{{ task.originalQuery }}</div>
              <div class="task-meta">
                <span>{{ task.executionMode || '-' }}</span>
                <span>轮次 {{ task.iterationCount || 0 }}</span>
                <span>工具 {{ task.toolCallCount || 0 }}</span>
              </div>
              <div v-if="task.currentSummary" class="task-summary">{{ task.currentSummary }}</div>
            </div>
          </div>
          <a-empty v-else description="暂无任务" />
        </a-card>
      </a-col>

      <a-col :xs="24" :lg="14">
        <a-card title="任务详情" class="section-card">
          <template #extra>
            <a-space>
              <a-button v-if="detail?.resumable" type="primary" @click="openResumeModal">恢复执行</a-button>
              <a-button v-if="selectedTaskId" @click="loadTaskDetail(selectedTaskId)" :loading="detailLoading">刷新详情</a-button>
            </a-space>
          </template>

          <div v-if="detail" class="detail-wrap">
            <a-descriptions :column="2" bordered size="small">
              <a-descriptions-item label="任务ID">{{ detail.taskId }}</a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-tag :color="statusColor(detail.status)">{{ detail.status }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="执行模式">{{ detail.executionMode || '-' }}</a-descriptions-item>
              <a-descriptions-item label="可恢复">{{ detail.resumable ? '是' : '否' }}</a-descriptions-item>
              <a-descriptions-item label="下一步">{{ detail.nextStepId || '-' }}</a-descriptions-item>
              <a-descriptions-item label="剩余步骤">{{ detail.remainingStepCount || 0 }}</a-descriptions-item>
            </a-descriptions>

            <div class="detail-block">
              <div class="block-title">原始问题</div>
              <div class="block-content">{{ detail.originalQuery }}</div>
            </div>

            <div v-if="detail.currentSummary" class="detail-block">
              <div class="block-title">当前摘要</div>
              <div class="block-content">{{ detail.currentSummary }}</div>
            </div>

            <div v-if="detail.failureReason" class="detail-block danger">
              <div class="block-title">失败原因</div>
              <div class="block-content">{{ detail.failureReason }}</div>
            </div>

            <div class="detail-block">
              <div class="block-title">剩余步骤标签</div>
              <div class="step-labels">
                <a-tag v-for="label in detail.remainingStepLabels || []" :key="label" color="arcoblue">{{ label }}</a-tag>
                <span v-if="!(detail.remainingStepLabels && detail.remainingStepLabels.length)">-</span>
              </div>
            </div>

            <div class="detail-block">
              <div class="block-title">执行步骤</div>
              <a-timeline>
                <a-timeline-item v-for="step in detail.steps || []" :key="step.stepId" :color="statusColor(step.status)">
                  <template #dot>
                    <icon-check-circle v-if="step.status === 'COMPLETED'" :style="{ fontSize: '16px', color: 'rgb(var(--green-6))' }" />
                    <icon-loading v-else-if="step.status === 'RUNNING'" :style="{ fontSize: '16px', color: 'rgb(var(--arcoblue-6))' }" />
                    <icon-close-circle v-else-if="step.status === 'FAILED' || step.status === 'BLOCKED'" :style="{ fontSize: '16px', color: 'rgb(var(--red-6))' }" />
                    <icon-record v-else :style="{ fontSize: '16px', color: 'var(--color-text-4)' }" />
                  </template>
                  <div class="timeline-step">
                    <div class="timeline-title">{{ step.label || step.stepId }}</div>
                    <div class="timeline-sub">
                      <a-tag :color="statusColor(step.status)" size="small">{{ step.status }}</a-tag>
                      <span>{{ step.goal || '-' }}</span>
                    </div>
                    <div v-if="step.resultSummary" class="timeline-text">{{ step.resultSummary }}</div>
                  </div>
                </a-timeline-item>
              </a-timeline>
            </div>

            <div class="detail-block">
              <div class="block-title">执行事件时间线</div>
              <a-timeline>
                <a-timeline-item
                  v-for="event in detail.events || []"
                  :key="`${event.createdAt}-${event.eventType}`"
                  :color="eventColor(event.eventType)"
                >
                  <template #dot>
                    <icon-check-circle v-if="event.eventType.includes('COMPLETED') || event.eventType.includes('SUCCEEDED')" :style="{ fontSize: '14px' }" />
                    <icon-close-circle v-else-if="event.eventType.includes('FAILED') || event.eventType.includes('BLOCKED')" :style="{ fontSize: '14px' }" />
                    <icon-info-circle v-else-if="event.eventType.includes('REFLECTION') || event.eventType.includes('REPLAN')" :style="{ fontSize: '14px' }" />
                    <icon-record v-else :style="{ fontSize: '14px' }" />
                  </template>
                  <div class="timeline-event">
                    <div class="timeline-title">{{ formatEventType(event.eventType) }}</div>
                    <div class="timeline-sub">{{ event.createdAt || '-' }}</div>
                    <div v-if="event.payloadJson" class="timeline-payload">
                      <a-collapse :bordered="false" size="small">
                        <a-collapse-item key="1" header="查看详情">
                          <pre class="payload-content">{{ formatPayload(event.payloadJson) }}</pre>
                        </a-collapse-item>
                      </a-collapse>
                    </div>
                  </div>
                </a-timeline-item>
              </a-timeline>
            </div>
          </div>
          <a-empty v-else description="请选择任务" />
        </a-card>
      </a-col>
    </a-row>

    <a-modal v-model:visible="resumeVisible" title="恢复任务" @before-ok="handleResume">
      <a-form :model="resumeForm" layout="vertical">
        <a-form-item label="补充信息">
          <a-textarea v-model="resumeMessage" :rows="4" placeholder="可选。补充上下文、约束、修正信息。" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import {
  IconRefresh,
  IconCheckCircle,
  IconCloseCircle,
  IconInfoCircle,
  IconRecord,
  IconLoading
} from '@arco-design/web-vue/es/icon'
import { agentTaskApi } from '@/api'

const loading = ref(false)
const detailLoading = ref(false)
const tasks = ref<any[]>([])
const detail = ref<any | null>(null)
const selectedTaskId = ref('')
const resumeVisible = ref(false)
const resumeMessage = ref('')
const resumeForm = ref({ userMessage: '' })

onMounted(async () => {
  await loadTasks()
})

async function loadTasks() {
  loading.value = true
  try {
    const res: any = await agentTaskApi.list()
    const data = res?.data || res || {}
    tasks.value = data.tasks || []
    if (!selectedTaskId.value && tasks.value.length) {
      selectedTaskId.value = tasks.value[0].taskId
      await loadTaskDetail(selectedTaskId.value)
    }
  } catch (e) {
    Message.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

async function selectTask(taskId: string) {
  selectedTaskId.value = taskId
  await loadTaskDetail(taskId)
}

async function loadTaskDetail(taskId: string) {
  detailLoading.value = true
  try {
    const res: any = await agentTaskApi.detail(taskId)
    detail.value = res?.data || res || null
  } catch (e) {
    Message.error('加载任务详情失败')
  } finally {
    detailLoading.value = false
  }
}

function openResumeModal() {
  resumeMessage.value = ''
  resumeForm.value.userMessage = ''
  resumeVisible.value = true
}

async function handleResume() {
  if (!selectedTaskId.value) return false
  try {
    const res: any = await agentTaskApi.resume(selectedTaskId.value, { userMessage: resumeMessage.value || undefined })
    const data = res?.data || res || {}
    Message.success(`已触发恢复执行，剩余步骤 ${data.remainingStepCount ?? 0}`)
    resumeVisible.value = false
    await loadTasks()
    await loadTaskDetail(selectedTaskId.value)
    return true
  } catch (e) {
    Message.error('恢复任务失败')
    return false
  }
}

function statusColor(status?: string) {
  if (status === 'COMPLETED') return 'green'
  if (status === 'RUNNING') return 'arcoblue'
  if (status === 'WAITING_USER') return 'orange'
  if (status === 'BLOCKED') return 'red'
  if (status === 'FAILED') return 'red'
  return 'gray'
}

function eventColor(eventType?: string) {
  if (!eventType) return 'gray'
  if (eventType.includes('FAILED') || eventType.includes('BLOCKED')) return 'red'
  if (eventType.includes('REFLECTION') || eventType.includes('REPLAN')) return 'orange'
  if (eventType.includes('COMPLETED') || eventType.includes('SUCCEEDED')) return 'green'
  return 'arcoblue'
}

function formatEventType(eventType: string) {
  const typeMap: Record<string, string> = {
    TASK_CREATED: '任务创建',
    PLAN_CREATED: '计划生成',
    STEP_STARTED: '步骤开始',
    STEP_COMPLETED: '步骤完成',
    STEP_FAILED: '步骤失败',
    STEP_SKIPPED: '步骤跳过',
    TOOL_CALLED: '工具调用',
    TOOL_SUCCEEDED: '工具成功',
    TOOL_FAILED: '工具失败',
    REFLECTION_CREATED: '反思分析',
    REPLAN_CREATED: '重新规划',
    TASK_BLOCKED: '任务阻塞',
    TASK_COMPLETED: '任务完成',
    TASK_FAILED: '任务失败'
  }
  return typeMap[eventType] || eventType
}

function formatPayload(payloadJson: string) {
  try {
    const payload = JSON.parse(payloadJson)
    return JSON.stringify(payload, null, 2)
  } catch {
    return payloadJson
  }
}
</script>

<style scoped>
.agent-tasks-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header h2 {
  margin: 0;
}

.page-desc {
  color: var(--color-text-3);
  font-size: 13px;
  margin-top: 4px;
}

.section-card {
  height: 100%;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 800px;
  overflow-y: auto;
}

.task-item {
  padding: 12px;
  border: 1px solid var(--color-border-2);
  border-radius: 8px;
  cursor: pointer;
}

.task-item.active {
  border-color: rgb(var(--primary-6));
  background: var(--color-fill-2);
}

.task-item-header {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.task-query {
  font-weight: 600;
  margin-bottom: 6px;
  word-break: break-word;
}

.task-meta {
  display: flex;
  gap: 12px;
  color: var(--color-text-3);
  font-size: 12px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.task-summary,
.block-content,
.timeline-text {
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-2);
  font-size: 13px;
}

.detail-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-block {
  padding: 12px;
  background: var(--color-fill-1);
  border-radius: 8px;
}

.detail-block.danger {
  background: rgba(245, 63, 63, 0.06);
}

.block-title,
.timeline-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.timeline-sub {
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 4px;
}

.step-labels {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.timeline-step,
.timeline-event {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.timeline-payload {
  margin-top: 8px;
}

.payload-content {
  margin: 0;
  padding: 12px;
  background: var(--color-fill-2);
  border-radius: 4px;
  font-size: 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
