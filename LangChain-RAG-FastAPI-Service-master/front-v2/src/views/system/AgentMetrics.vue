<template>
  <div class="agent-metrics">
    <!-- 顶部操作栏 -->
    <div class="metrics-header">
      <h2>Agent 质量评估</h2>
      <a-space>
        <a-range-picker v-model="dateRange" @change="handleDateChange" />
        <a-button @click="fetchMetrics" :loading="loading">
          <template #icon><icon-refresh /></template>
          刷新数据
        </a-button>
        <a-button type="outline" @click="fetchToday">
          <template #icon><icon-calendar /></template>
          今日数据
        </a-button>
      </a-space>
    </div>

    <!-- 核心指标卡片 -->
    <a-row :gutter="16" class="stats-row">
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card">
          <a-statistic title="任务总数" :value="metrics.totalTasks || 0" />
        </a-card>
      </a-col>
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card success">
          <a-statistic title="完成任务数" :value="metrics.completedTasks || 0" />
        </a-card>
      </a-col>
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card primary">
          <a-statistic title="任务完成率" :value="metrics.completionRate || 0">
            <template #suffix>%</template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card warning">
          <a-statistic title="阻塞任务数" :value="metrics.blockedTasks || 0" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 执行效率指标 -->
    <a-row :gutter="16" class="content-row">
      <a-col :xs="24" :md="12">
        <a-card title="执行效率" class="section-card">
          <div class="metrics-grid">
            <div class="metric-item">
              <div class="metric-info">
                <span>平均迭代轮次</span>
                <span class="metric-value">{{ formatNumber(metrics.avgIterationCount) }}</span>
              </div>
              <a-progress
                :percent="Math.min((metrics.avgIterationCount || 0) * 10, 100)"
                :show-text="false"
                color="#3491FA"
                size="small"
              />
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>平均工具调用次数</span>
                <span class="metric-value">{{ formatNumber(metrics.avgToolCallCount) }}</span>
              </div>
              <a-progress
                :percent="Math.min((metrics.avgToolCallCount || 0) * 5, 100)"
                :show-text="false"
                color="#00D0B6"
                size="small"
              />
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>平均 Token 消耗</span>
                <span class="metric-value">{{ formatNumber(metrics.avgTokenConsumed) }}</span>
              </div>
              <a-progress
                :percent="Math.min((metrics.avgTokenConsumed || 0) / 100, 100)"
                :show-text="false"
                color="#F77234"
                size="small"
              />
            </div>
          </div>
        </a-card>
      </a-col>

      <a-col :xs="24" :md="12">
        <a-card title="工具执行" class="section-card">
          <div class="metrics-grid">
            <div class="metric-item">
              <div class="metric-info">
                <span>工具执行总数</span>
                <span class="metric-value">{{ metrics.totalToolExecutions || 0 }}</span>
              </div>
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>工具成功率</span>
                <span class="metric-value">{{ formatPercent(metrics.toolSuccessRate) }}</span>
              </div>
              <a-progress
                :percent="metrics.toolSuccessRate || 0"
                :show-text="false"
                :color="getSuccessRateColor(metrics.toolSuccessRate)"
                size="small"
              />
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>平均工具耗时</span>
                <span class="metric-value">{{ formatLatency(metrics.avgToolLatencyMs) }}</span>
              </div>
              <a-progress
                :percent="Math.min((metrics.avgToolLatencyMs || 0) / 50, 100)"
                :show-text="false"
                color="#722ED1"
                size="small"
              />
            </div>
          </div>
        </a-card>
      </a-col>
    </a-row>

    <!-- 反思与异常指标 -->
    <a-row :gutter="16" class="content-row">
      <a-col :xs="24" :md="12">
        <a-card title="反思与重规划" class="section-card">
          <div class="metrics-grid">
            <div class="metric-item">
              <div class="metric-info">
                <span>反思次数</span>
                <span class="metric-value">{{ metrics.reflectionCount || 0 }}</span>
              </div>
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>重规划次数</span>
                <span class="metric-value">{{ metrics.replanCount || 0 }}</span>
              </div>
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>重规划率</span>
                <span class="metric-value">{{ formatPercent(replanRate) }}</span>
              </div>
              <a-progress
                :percent="replanRate || 0"
                :show-text="false"
                color="#F5319D"
                size="small"
              />
            </div>
          </div>
        </a-card>
      </a-col>

      <a-col :xs="24" :md="12">
        <a-card title="异常与阻塞" class="section-card">
          <div class="metrics-grid">
            <div class="metric-item">
              <div class="metric-info">
                <span>失败任务数</span>
                <span class="metric-value error">{{ metrics.failedTasks || 0 }}</span>
              </div>
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>URL 拦截次数</span>
                <span class="metric-value warning">{{ metrics.urlBlockedCount || 0 }}</span>
              </div>
            </div>

            <div class="metric-item">
              <div class="metric-info">
                <span>失败率</span>
                <span class="metric-value">{{ formatPercent(failureRate) }}</span>
              </div>
              <a-progress
                :percent="failureRate || 0"
                :show-text="false"
                status="danger"
                size="small"
              />
            </div>
          </div>
        </a-card>
      </a-col>
    </a-row>

    <!-- 时间范围说明 -->
    <a-card class="info-card" v-if="dateRangeText">
      <a-space>
        <icon-info-circle />
        <span>{{ dateRangeText }}</span>
      </a-space>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Message } from '@arco-design/web-vue'
import { agentMetricsApi } from '@/api'
import dayjs from 'dayjs'

interface AgentMetrics {
  totalTasks: number
  completedTasks: number
  failedTasks: number
  blockedTasks: number
  completionRate: number
  avgIterationCount: number
  avgToolCallCount: number
  avgTokenConsumed: number
  reflectionCount: number
  replanCount: number
  urlBlockedCount: number
  totalToolExecutions: number
  successfulToolExecutions: number
  toolSuccessRate: number
  avgToolLatencyMs: number
}

const loading = ref(false)
const metrics = ref<AgentMetrics>({
  totalTasks: 0,
  completedTasks: 0,
  failedTasks: 0,
  blockedTasks: 0,
  completionRate: 0,
  avgIterationCount: 0,
  avgToolCallCount: 0,
  avgTokenConsumed: 0,
  reflectionCount: 0,
  replanCount: 0,
  urlBlockedCount: 0,
  totalToolExecutions: 0,
  successfulToolExecutions: 0,
  toolSuccessRate: 0,
  avgToolLatencyMs: 0,
})

const dateRange = ref<[string, string]>([
  dayjs().subtract(6, 'day').format('YYYY-MM-DD'),
  dayjs().format('YYYY-MM-DD'),
])

const dateRangeText = computed(() => {
  if (!dateRange.value || dateRange.value.length !== 2) return ''
  return `数据范围：${dateRange.value[0]} 至 ${dateRange.value[1]}`
})

const replanRate = computed(() => {
  if (!metrics.value.reflectionCount) return 0
  return Math.round((metrics.value.replanCount / metrics.value.reflectionCount) * 100)
})

const failureRate = computed(() => {
  if (!metrics.value.totalTasks) return 0
  return Math.round((metrics.value.failedTasks / metrics.value.totalTasks) * 100)
})

const formatNumber = (val: number | undefined) => {
  if (val == null) return '-'
  return val.toFixed(2)
}

const formatPercent = (val: number | undefined) => {
  if (val == null) return '-'
  return `${val.toFixed(1)}%`
}

const formatLatency = (ms: number | undefined) => {
  if (ms == null) return '-'
  return `${ms.toFixed(0)} ms`
}

const getSuccessRateColor = (rate: number | undefined) => {
  if (rate == null) return '#C9CDD4'
  if (rate >= 90) return '#00B42A'
  if (rate >= 70) return '#FF7D00'
  return '#F53F3F'
}

const fetchMetrics = async () => {
  if (!dateRange.value || dateRange.value.length !== 2) {
    Message.warning('请选择日期范围')
    return
  }

  loading.value = true
  try {
    const start = dateRange.value[0]
    const end = dateRange.value[1]
    const res = await agentMetricsApi.getMetrics(start, end)
    metrics.value = res.data
  } catch (error: any) {
    Message.error(error.message || '加载指标失败')
  } finally {
    loading.value = false
  }
}

const fetchToday = async () => {
  loading.value = true
  try {
    const res = await agentMetricsApi.getToday()
    metrics.value = res.data
    const today = dayjs().format('YYYY-MM-DD')
    dateRange.value = [today, today]
  } catch (error: any) {
    Message.error(error.message || '加载今日数据失败')
  } finally {
    loading.value = false
  }
}

const handleDateChange = () => {
  fetchMetrics()
}

onMounted(() => {
  fetchMetrics()
})
</script>

<style scoped>
.agent-metrics {
  padding: 20px;
  background: var(--color-bg-2);
  min-height: calc(100vh - 60px);
}

.metrics-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border-2);

  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    color: var(--color-text-1);
  }
}

.stats-row {
  margin-bottom: 16px;
}

.content-row {
  margin-bottom: 16px;
}

.stat-card {
  background: var(--color-bg-1);
  border: 1px solid var(--color-border-2);
  border-radius: 8px;

  :deep(.arco-card-body) {
    padding: 20px;
  }

  :deep(.arco-statistic-title) {
    color: var(--color-text-2);
    font-size: 14px;
    margin-bottom: 8px;
  }

  :deep(.arco-statistic-value) {
    color: var(--color-text-1);
    font-size: 28px;
    font-weight: 600;
  }

  &.success :deep(.arco-statistic-value) {
    color: #00B42A;
  }

  &.primary :deep(.arco-statistic-value) {
    color: #38BDF8;
  }

  &.warning :deep(.arco-statistic-value) {
    color: #F97316;
  }
}

.section-card {
  background: var(--color-bg-1);
  border: 1px solid var(--color-border-2);
  border-radius: 8px;
  height: 100%;

  :deep(.arco-card-header) {
    border-bottom: 1px solid var(--color-border-2);
    padding: 16px 20px;
  }

  :deep(.arco-card-header-title) {
    color: var(--color-text-1);
    font-size: 16px;
    font-weight: 600;
  }

  :deep(.arco-card-body) {
    padding: 20px;
  }
}

.metrics-grid {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.metric-item {
  .metric-info {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;

    span:first-child {
      color: var(--color-text-2);
      font-size: 14px;
    }

    .metric-value {
      color: var(--color-text-1);
      font-size: 18px;
      font-weight: 600;

      &.error {
        color: #F53F3F;
      }

      &.warning {
        color: #F97316;
      }
    }
  }
}

.info-card {
  background: var(--color-bg-1);
  border: 1px solid var(--color-border-2);
  border-radius: 8px;
  margin-top: 16px;

  :deep(.arco-card-body) {
    padding: 12px 20px;
    color: var(--color-text-2);
    font-size: 14px;
  }
}
</style>
