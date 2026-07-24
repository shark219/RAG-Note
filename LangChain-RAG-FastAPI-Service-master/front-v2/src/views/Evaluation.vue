<template>
  <div class="evaluation">
    <!-- 顶部操作栏 -->
    <div class="eval-header">
      <h2>RAG 质量评估</h2>
      <a-space>
        <a-button type="primary" @click="handleRunBatch" :loading="evaluating">
          <template #icon><icon-play-arrow /></template>
          批量评估
        </a-button>
        <a-button status="success" @click="handleGenerateTestCases" :loading="generating">
          <template #icon><icon-plus /></template>
          生成测试用例
        </a-button>
        <a-button status="warning" @click="handleRegression" :loading="regressing">
          <template #icon><icon-refresh /></template>
          回归测试
        </a-button>
      </a-space>
    </div>

    <!-- 统计卡片 -->
    <a-row :gutter="16" class="stats-row">
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card">
          <a-statistic title="今日查询" :value="stats.todayTraceCount || 0" />
        </a-card>
      </a-col>
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card">
          <a-statistic title="周平均分" :value="stats.weekAvgScore ? Number(stats.weekAvgScore.toFixed(0)) : 0" />
        </a-card>
      </a-col>
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card">
          <a-statistic title="低分样本" :value="stats.weekLowScoreCount || 0" />
        </a-card>
      </a-col>
      <a-col :xs="12" :sm="12" :md="6">
        <a-card class="stat-card">
          <a-statistic title="平均耗时" :value="stats.todayAvgLatency ? (stats.todayAvgLatency / 1000).toFixed(1) + 's' : '-'" />
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="16" class="content-row">
      <!-- 左列 -->
      <a-col :xs="24" :md="16">
        <!-- 四指标进度条 -->
        <a-card title="四指标均值（本周）" class="section-card">
          <div class="metrics-grid">
            <div class="metric-item">
              <div class="metric-info">
                <span>忠实度</span>
                <span>{{ formatPercent(stats.weekAvgFaithfulness) }}</span>
              </div>
              <a-progress :percent="(stats.weekAvgFaithfulness || 0)" :show-text="false" color="#D4914A" size="small" />
            </div>
            <div class="metric-item">
              <div class="metric-info">
                <span>答案相关性</span>
                <span>{{ formatPercent(stats.weekAvgRelevancy) }}</span>
              </div>
              <a-progress :percent="(stats.weekAvgRelevancy || 0)" :show-text="false" color="#165dff" size="small" />
            </div>
            <div class="metric-item">
              <div class="metric-info">
                <span>上下文精度</span>
                <span>{{ formatPercent(stats.weekAvgPrecision) }}</span>
              </div>
              <a-progress :percent="(stats.weekAvgPrecision || 0)" :show-text="false" color="#00b42a" size="small" />
            </div>
            <div class="metric-item">
              <div class="metric-info">
                <span>上下文召回</span>
                <span>{{ formatPercent(stats.weekAvgRecall) }}</span>
              </div>
              <a-progress :percent="(stats.weekAvgRecall || 0)" :show-text="false" color="#722ed1" size="small" />
            </div>
          </div>
        </a-card>

        <!-- 趋势图 -->
        <a-card :title="`趋势（最近 ${trendData.dates?.length || 0} 天）`" class="section-card">
          <div v-if="trendData.dates && trendData.dates.length > 0" class="trend-chart">
            <div v-for="(date, i) in trendData.dates" :key="date" class="trend-row">
              <span class="trend-date">{{ date.substring(5) }}</span>
              <div class="trend-bar-container">
                <div class="trend-bar" :style="{ width: (trendData.totalScore?.[i] || 0) + '%' }"></div>
              </div>
              <span class="trend-value">{{ trendData.totalScore?.[i]?.toFixed(0) || '-' }}</span>
            </div>
          </div>
          <a-empty v-else description="暂无趋势数据" />
        </a-card>

        <!-- 回归测试结果 -->
        <a-card v-if="regressionResult" title="回归测试结果" class="section-card">
          <a-descriptions :column="2" bordered size="small">
            <a-descriptions-item label="用例数">{{ regressionResult.total }}</a-descriptions-item>
            <a-descriptions-item label="成功率">{{ regressionResult.success }}/{{ regressionResult.total }}</a-descriptions-item>
            <a-descriptions-item label="平均分">
              <a-tag :color="getScoreColor(regressionResult.avgScore)">{{ regressionResult.avgScore }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="忠实度">{{ (regressionResult.avgFaithfulness * 100).toFixed(0) }}%</a-descriptions-item>
            <a-descriptions-item label="相关性">{{ (regressionResult.avgRelevancy * 100).toFixed(0) }}%</a-descriptions-item>
          </a-descriptions>
        </a-card>

        <!-- 评估报告表格 -->
        <a-card title="评估报告" class="section-card">
          <a-table :data="reports" :pagination="{ pageSize: 10 }" :bordered="false" size="small">
            <template #columns>
              <a-table-column title="Trace ID" :width="120">
                <template #cell="{ record }">
                  <a-tag size="small">{{ record.traceId?.slice(0, 8) }}...</a-tag>
                </template>
              </a-table-column>
              <a-table-column title="查询" data-index="query" :ellipsis="true" />
              <a-table-column title="质量分" :width="140">
                <template #cell="{ record }">
                  <a-space>
                    <a-progress
                      :percent="(record.totalScore || 0)"
                      :color="getScoreColor(record.totalScore)"
                      :show-text="false"
                      size="small"
                      :style="{ width: '60px' }"
                    />
                    <a-tag :color="getScoreColor(record.totalScore)" size="small">
                      {{ record.totalScore || '-' }}
                    </a-tag>
                  </a-space>
                </template>
              </a-table-column>
              <a-table-column title="评级" :width="80" align="center">
                <template #cell="{ record }">
                  <a-tag :color="getLevelColor(record.level)" size="small">{{ record.level || '-' }}</a-tag>
                </template>
              </a-table-column>
              <a-table-column title="时间" :width="120">
                <template #cell="{ record }">
                  {{ formatDate(record.createdAt) }}
                </template>
              </a-table-column>
              <a-table-column title="操作" :width="80" align="center">
                <template #cell="{ record }">
                  <a-button type="text" size="mini" @click="showDetail(record)">详情</a-button>
                </template>
              </a-table-column>
            </template>
          </a-table>
        </a-card>
      </a-col>

      <!-- 右列 -->
      <a-col :xs="24" :md="8">
        <!-- 评级分布 -->
        <a-card title="评级分布（30天）" class="section-card">
          <div class="distribution-grid">
            <div class="dist-item">
              <div class="dist-count excellent">{{ distribution.excellent || 0 }}</div>
              <div class="dist-label">优秀</div>
            </div>
            <div class="dist-item">
              <div class="dist-count good">{{ distribution.good || 0 }}</div>
              <div class="dist-label">良好</div>
            </div>
            <div class="dist-item">
              <div class="dist-count pass">{{ distribution.pass || 0 }}</div>
              <div class="dist-label">及格</div>
            </div>
            <div class="dist-item">
              <div class="dist-count fail">{{ distribution.fail || 0 }}</div>
              <div class="dist-label">不及格</div>
            </div>
          </div>
        </a-card>

        <!-- 问题类型分布 -->
        <a-card title="问题类型（30天）" class="section-card">
          <div class="diagnosis-grid">
            <div class="diag-item">
              <span class="diag-count">{{ diagnosisStats.hallucination || 0 }}</span>
              <span class="diag-label">幻觉</span>
            </div>
            <div class="diag-item">
              <span class="diag-count">{{ diagnosisStats.irrelevant || 0 }}</span>
              <span class="diag-label">答非所问</span>
            </div>
            <div class="diag-item">
              <span class="diag-count">{{ diagnosisStats.retrievalLow || 0 }}</span>
              <span class="diag-label">检索精度低</span>
            </div>
            <div class="diag-item">
              <span class="diag-count">{{ diagnosisStats.recallLow || 0 }}</span>
              <span class="diag-label">检索召回低</span>
            </div>
            <div class="diag-item">
              <span class="diag-count">{{ diagnosisStats.normal || 0 }}</span>
              <span class="diag-label">正常</span>
            </div>
          </div>
        </a-card>

        <!-- 低分样本 -->
        <a-card :title="`低分样本（${lowScores.length}）`" class="section-card">
          <div v-if="lowScores.length > 0" class="low-score-list">
            <div v-for="item in lowScores" :key="item.id" class="low-score-item" @click="showDetail(item)">
              <div class="low-score-header">
                <a-tag :color="getScoreColor(item.totalScore)" size="small">{{ item.totalScore }}</a-tag>
                <a-tag size="small">{{ item.level }}</a-tag>
              </div>
              <div class="low-score-query">{{ item.query }}</div>
              <div class="low-score-diagnosis">{{ item.diagnosis }}</div>
            </div>
          </div>
          <a-empty v-else description="暂无低分样本" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 报告详情弹窗 -->
    <a-modal v-model:visible="detailVisible" title="评估详情" :width="600" :footer="false">
      <div v-if="selectedReport" class="detail-content">
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item label="用户问题">{{ selectedReport.query }}</a-descriptions-item>
          <a-descriptions-item label="忠实度">{{ formatPercent(selectedReport.faithfulness) }}</a-descriptions-item>
          <a-descriptions-item label="答案相关性">{{ formatPercent(selectedReport.answerRelevancy) }}</a-descriptions-item>
          <a-descriptions-item label="上下文精度">{{ formatPercent(selectedReport.contextPrecision) }}</a-descriptions-item>
          <a-descriptions-item label="上下文召回">{{ selectedReport.contextRecall != null ? formatPercent(selectedReport.contextRecall) : '无标准答案' }}</a-descriptions-item>
          <a-descriptions-item label="规则评分">{{ selectedReport.ruleScore || '-' }}</a-descriptions-item>
          <a-descriptions-item label="调和平均数">{{ selectedReport.harmonicMean ? selectedReport.harmonicMean.toFixed(3) : '-' }}</a-descriptions-item>
          <a-descriptions-item label="综合评分">
            <a-tag :color="getScoreColor(selectedReport.totalScore)" size="large">{{ selectedReport.totalScore }}</a-tag>
          </a-descriptions-item>
        </a-descriptions>
        <div class="detail-diagnosis">
          <h4>诊断结果</h4>
          <p>{{ selectedReport.diagnosis || '暂无诊断' }}</p>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconPlayArrow, IconPlus, IconRefresh } from '@arco-design/web-vue/es/icon'
import { evaluationApi } from '@/api'
import dayjs from 'dayjs'

// ========== 状态 ==========
const evaluating = ref(false)
const generating = ref(false)
const regressing = ref(false)
const stats = ref<any>({})
const reports = ref<any[]>([])
const lowScores = ref<any[]>([])
const trendData = ref<any>({})
const distribution = ref<any>({})
const diagnosisStats = ref<any>({})
const regressionResult = ref<any>(null)

// 详情弹窗
const detailVisible = ref(false)
const selectedReport = ref<any>(null)

// ========== 生命周期 ==========
onMounted(() => {
  refreshAll()
})

// ========== 数据加载 ==========
async function refreshAll() {
  await Promise.all([
    fetchStats(),
    fetchReports(),
    fetchLowScores(),
    fetchTrend(),
    fetchDistribution(),
    fetchDiagnosisStats(),
  ])
}

async function fetchStats() {
  try {
    const res: any = await evaluationApi.getStats()
    stats.value = res?.data || res || {}
  } catch (e) {
    console.error('获取统计失败', e)
  }
}

async function fetchReports() {
  try {
    const res: any = await evaluationApi.getReports()
    reports.value = res?.data || res || []
  } catch (e) {
    console.error('获取报告失败', e)
  }
}

async function fetchLowScores() {
  try {
    const res: any = await evaluationApi.getLowScores()
    lowScores.value = res?.data || res || []
  } catch (e) {
    console.error('获取低分样本失败', e)
  }
}

async function fetchTrend() {
  try {
    const res: any = await evaluationApi.getTrend(14)
    trendData.value = res?.data || res || {}
  } catch (e) {
    console.error('获取趋势失败', e)
  }
}

async function fetchDistribution() {
  try {
    const res: any = await evaluationApi.getDistribution(30)
    distribution.value = res?.data || res || {}
  } catch (e) {
    console.error('获取分布失败', e)
  }
}

async function fetchDiagnosisStats() {
  try {
    const res: any = await evaluationApi.getDiagnosisStats(30)
    diagnosisStats.value = res?.data || res || {}
  } catch (e) {
    console.error('获取诊断分布失败', e)
  }
}

// ========== 操作 ==========
async function handleRunBatch() {
  evaluating.value = true
  try {
    const res: any = await evaluationApi.runBatch(7)
    const data = res?.data || res
    Message.success(`评估完成: ${data.evaluated}/${data.total}`)
    refreshAll()
  } catch (e) {
    Message.error('评估失败')
  } finally {
    evaluating.value = false
  }
}

async function handleGenerateTestCases() {
  generating.value = true
  try {
    const res: any = await evaluationApi.generateTestCases(10)
    const data = res?.data || res
    Message.success(`生成完成: ${data.generated} 条测试用例`)
  } catch (e) {
    Message.error('生成失败')
  } finally {
    generating.value = false
  }
}

async function handleRegression() {
  regressing.value = true
  try {
    const res: any = await evaluationApi.runRegression()
    const data = res?.data || res
    regressionResult.value = data
    Message.success(`回归测试完成: 平均分 ${data.avgScore}`)
    refreshAll()
  } catch (e) {
    Message.error('回归测试失败')
  } finally {
    regressing.value = false
  }
}

// ========== 辅助函数 ==========
function showDetail(report: any) {
  selectedReport.value = report
  detailVisible.value = true
}

function formatPercent(val: number | null | undefined) {
  if (val == null) return '-'
  return (val * 100).toFixed(0) + '%'
}

function formatDate(date: string) {
  if (!date) return '-'
  return dayjs(date).format('MM-DD HH:mm')
}

function getScoreColor(score: number) {
  if (score >= 90) return '#00b42a'
  if (score >= 75) return '#165dff'
  if (score >= 60) return '#ff7d00'
  return '#f53f3f'
}

function getLevelColor(level: string) {
  const map: Record<string, string> = {
    '优秀': 'green',
    '良好': 'blue',
    '及格': 'orange',
    '不及格': 'red',
  }
  return map[level] || 'gray'
}
</script>

<style scoped>
.evaluation {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.eval-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.eval-header h2 {
  margin: 0;
}

.stats-row {
  margin-bottom: 0;
}

.stat-card {
  text-align: center;
}

.content-row {
  margin-top: 0;
}

.section-card {
  margin-bottom: 16px;
}

/* 四指标 */
.metrics-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.metric-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-info {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--color-text-2);
}

/* 趋势图 */
.trend-chart {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.trend-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trend-date {
  font-size: 12px;
  color: var(--color-text-3);
  width: 40px;
  text-align: right;
  flex-shrink: 0;
}

.trend-bar-container {
  flex: 1;
  height: 18px;
  background: var(--color-fill-3);
  border-radius: 4px;
  overflow: hidden;
}

.trend-bar {
  height: 100%;
  background: linear-gradient(90deg, #D4914A, #B8926E);
  border-radius: 4px;
  transition: width 0.3s;
  min-width: 2px;
}

.trend-value {
  font-size: 12px;
  color: var(--color-text-2);
  width: 30px;
  flex-shrink: 0;
}

/* 评级分布 */
.distribution-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  text-align: center;
}

.dist-item {
  padding: 12px;
  background: var(--color-fill-2);
  border-radius: 8px;
}

.dist-count {
  font-size: 24px;
  font-weight: 700;
}

.dist-count.excellent { color: #00b42a; }
.dist-count.good { color: #165dff; }
.dist-count.pass { color: #ff7d00; }
.dist-count.fail { color: #f53f3f; }

.dist-label {
  font-size: 12px;
  color: var(--color-text-3);
  margin-top: 4px;
}

/* 问题类型 */
.diagnosis-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.diag-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 60px;
}

.diag-count {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text-1);
}

.diag-label {
  font-size: 11px;
  color: var(--color-text-3);
  margin-top: 4px;
}

/* 低分样本 */
.low-score-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 400px;
  overflow-y: auto;
}

.low-score-item {
  padding: 10px;
  background: var(--color-fill-2);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}

.low-score-item:hover {
  background: var(--color-fill-3);
}

.low-score-header {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}

.low-score-query {
  font-size: 13px;
  color: var(--color-text-1);
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.low-score-diagnosis {
  font-size: 12px;
  color: var(--color-text-3);
}

/* 详情弹窗 */
.detail-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-diagnosis {
  background: var(--color-fill-2);
  padding: 12px;
  border-radius: 8px;
}

.detail-diagnosis h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.detail-diagnosis p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-2);
}
</style>
