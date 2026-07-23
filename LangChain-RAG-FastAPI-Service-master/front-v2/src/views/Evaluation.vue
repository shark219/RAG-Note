<template>
  <div class="evaluation">
    <div class="eval-header">
      <h2>RAG 评估</h2>
      <a-button type="primary" @click="handleRunBatch" :loading="running">
        <template #icon><icon-play-arrow /></template>
        运行批量评估
      </a-button>
    </div>

    <a-row :gutter="16" style="margin-bottom: 20px">
      <a-col :span="6">
        <a-card>
          <a-statistic title="总评估数" :value="stats.total || 0" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="平均质量分" :value="stats.avgScore || 0" :precision="2" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="低分查询" :value="stats.lowScoreCount || 0" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="总反馈数" :value="stats.feedbackCount || 0" />
        </a-card>
      </a-col>
    </a-row>

    <a-card title="评估报告">
      <a-table :data="reports" :pagination="{ pageSize: 10 }">
        <template #columns>
          <a-table-column title="Trace ID" data-index="traceId">
            <template #cell="{ record }">
              <a-tag>{{ record.traceId?.slice(0, 8) }}...</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="查询" data-index="query" :ellipsis="true" />
          <a-table-column title="质量分" data-index="qualityScore">
            <template #cell="{ record }">
              <a-progress
                :percent="(record.qualityScore || 0) / 100"
                :color="getScoreColor(record.qualityScore)"
                :show-text="false"
                style="width: 100px; display: inline-block"
              />
              <span style="margin-left: 8px">{{ record.qualityScore?.toFixed(1) || '-' }}</span>
            </template>
          </a-table-column>
          <a-table-column title="延迟" data-index="totalLatencyMs">
            <template #cell="{ record }">
              {{ record.totalLatencyMs ? `${(record.totalLatencyMs / 1000).toFixed(1)}s` : '-' }}
            </template>
          </a-table-column>
          <a-table-column title="创建时间" data-index="createdAt">
            <template #cell="{ record }">
              {{ formatDate(record.createdAt) }}
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconPlayArrow } from '@arco-design/web-vue/es/icon'
import { evaluationApi } from '@/api'
import dayjs from 'dayjs'

const running = ref(false)
const stats = ref<any>({})
const reports = ref<any[]>([])

onMounted(() => {
  fetchStats()
  fetchReports()
})

async function fetchStats() {
  try {
    const res: any = await evaluationApi.getStats()
    stats.value = res || {}
  } catch (e) {
    console.error('获取统计失败', e)
  }
}

async function fetchReports() {
  try {
    const res: any = await evaluationApi.getReports()
    reports.value = res || []
  } catch (e) {
    console.error('获取报告失败', e)
  }
}

async function handleRunBatch() {
  running.value = true
  try {
    await evaluationApi.runBatch()
    Message.success('批量评估已启动')
    fetchStats()
    fetchReports()
  } catch (e) {
    Message.error('启动失败')
  } finally {
    running.value = false
  }
}

function getScoreColor(score: number) {
  if (score >= 80) return '#00b42a'
  if (score >= 60) return '#ff7d00'
  return '#f53f3f'
}

function formatDate(date: string) {
  return dayjs(date).format('MM-DD HH:mm')
}
</script>

<style scoped>
.evaluation {
  max-width: 1200px;
  margin: 0 auto;
}

.eval-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.eval-header h2 {
  margin: 0;
}
</style>
