<template>
  <div class="evaluation-container">
    <van-nav-bar title="评估后台" fixed left-arrow @click-left="$router.back()" />

    <div class="content">
      <!-- 统计卡片 -->
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-value">{{ stats.todayTraceCount || 0 }}</div>
          <div class="stat-label">今日查询</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ stats.weekAvgScore ? stats.weekAvgScore.toFixed(1) : '-' }}</div>
          <div class="stat-label">平均分</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ stats.weekLowScoreCount || 0 }}</div>
          <div class="stat-label">低分样本</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ stats.todayAvgLatency ? (stats.todayAvgLatency / 1000).toFixed(1) + 's' : '-' }}</div>
          <div class="stat-label">平均耗时</div>
        </div>
      </div>

      <!-- 四指标雷达图 -->
      <div class="section">
        <div class="section-title">四指标均值（本周）</div>
        <div class="metrics-grid">
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill" :style="{ width: (stats.weekAvgPrecision || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>上下文精度</span>
              <span>{{ stats.weekAvgPrecision ? (stats.weekAvgPrecision * 100).toFixed(0) + '%' : '-' }}</span>
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill" :style="{ width: (stats.weekAvgRecall || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>上下文召回</span>
              <span>{{ stats.weekAvgRecall ? (stats.weekAvgRecall * 100).toFixed(0) + '%' : '-' }}</span>
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill" :style="{ width: (stats.weekAvgFaithfulness || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>忠实度</span>
              <span>{{ stats.weekAvgFaithfulness ? (stats.weekAvgFaithfulness * 100).toFixed(0) + '%' : '-' }}</span>
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill" :style="{ width: (stats.weekAvgRelevancy || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>答案相关性</span>
              <span>{{ stats.weekAvgRelevancy ? (stats.weekAvgRelevancy * 100).toFixed(0) + '%' : '-' }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="section">
        <van-button type="primary" block @click="triggerBatchEvaluate" :loading="evaluating">
          触发批量评估
        </van-button>
      </div>

      <!-- 低分样本列表 -->
      <div class="section">
        <div class="section-title">低分样本（{{ lowScores.length }}）</div>
        <div v-if="lowScores.length === 0" class="empty">暂无低分样本</div>
        <div v-for="item in lowScores" :key="item.id" class="report-card" @click="showDetail(item)">
          <div class="report-header">
            <span class="report-score" :class="getScoreClass(item.totalScore)">{{ item.totalScore }}</span>
            <span class="report-level">{{ item.level }}</span>
          </div>
          <div class="report-query">{{ item.query }}</div>
          <div class="report-diagnosis">{{ item.diagnosis }}</div>
        </div>
      </div>

      <!-- 最近评估报告 -->
      <div class="section">
        <div class="section-title">最近评估报告</div>
        <div v-if="reports.length === 0" class="empty">暂无评估报告，请先触发批量评估</div>
        <div v-for="item in reports" :key="item.id" class="report-card" @click="showDetail(item)">
          <div class="report-header">
            <span class="report-score" :class="getScoreClass(item.totalScore)">{{ item.totalScore }}</span>
            <span class="report-level">{{ item.level }}</span>
            <span class="report-time">{{ formatTime(item.createdAt) }}</span>
          </div>
          <div class="report-query">{{ item.query }}</div>
          <div class="report-metrics">
            <span>精度: {{ item.contextPrecision ? (item.contextPrecision * 100).toFixed(0) + '%' : '-' }}</span>
            <span>召回: {{ item.contextRecall ? (item.contextRecall * 100).toFixed(0) + '%' : '-' }}</span>
            <span>忠实: {{ item.faithfulness ? (item.faithfulness * 100).toFixed(0) + '%' : '-' }}</span>
            <span>相关: {{ item.answerRelevancy ? (item.answerRelevancy * 100).toFixed(0) + '%' : '-' }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <van-popup v-model:show="showPopup" position="bottom" round :style="{ height: '70%' }">
      <div class="detail-popup" v-if="selectedReport">
        <div class="detail-title">评估详情</div>
        <div class="detail-query">
          <div class="detail-label">用户问题</div>
          <div>{{ selectedReport.query }}</div>
        </div>
        <div class="detail-scores">
          <div class="score-item">
            <span>上下文精度</span>
            <span>{{ selectedReport.contextPrecision ? (selectedReport.contextPrecision * 100).toFixed(0) + '%' : '-' }}</span>
          </div>
          <div class="score-item">
            <span>上下文召回</span>
            <span>{{ selectedReport.contextRecall ? (selectedReport.contextRecall * 100).toFixed(0) + '%' : '-' }}</span>
          </div>
          <div class="score-item">
            <span>忠实度</span>
            <span>{{ selectedReport.faithfulness ? (selectedReport.faithfulness * 100).toFixed(0) + '%' : '-' }}</span>
          </div>
          <div class="score-item">
            <span>答案相关性</span>
            <span>{{ selectedReport.answerRelevancy ? (selectedReport.answerRelevancy * 100).toFixed(0) + '%' : '-' }}</span>
          </div>
          <div class="score-item">
            <span>规则评分</span>
            <span>{{ selectedReport.ruleScore || '-' }}</span>
          </div>
          <div class="score-item total">
            <span>综合评分</span>
            <span :class="getScoreClass(selectedReport.totalScore)">{{ selectedReport.totalScore }}</span>
          </div>
        </div>
        <div class="detail-diagnosis">
          <div class="detail-label">诊断结果</div>
          <div>{{ selectedReport.diagnosis }}</div>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { showToast } from 'vant'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
const token = ref(localStorage.getItem('jwt_token') || '')

const stats = ref({})
const reports = ref([])
const lowScores = ref([])
const evaluating = ref(false)
const showPopup = ref(false)
const selectedReport = ref(null)

function getHeaders() {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token.value}`
  }
}

async function fetchStats() {
  try {
    const res = await fetch('/evaluation/stats', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) stats.value = json.data
  } catch (e) {
    console.error('获取统计失败:', e)
  }
}

async function fetchReports() {
  try {
    const res = await fetch('/evaluation/reports', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) reports.value = json.data || []
  } catch (e) {
    console.error('获取报告失败:', e)
  }
}

async function fetchLowScores() {
  try {
    const res = await fetch('/evaluation/low-scores', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) lowScores.value = json.data || []
  } catch (e) {
    console.error('获取低分样本失败:', e)
  }
}

async function triggerBatchEvaluate() {
  evaluating.value = true
  try {
    const res = await fetch('/evaluation/batch?days=7', {
      method: 'POST',
      headers: getHeaders()
    })
    const json = await res.json()
    if (json.code === 200) {
      showToast(`评估完成: ${json.data.evaluated}/${json.data.total}`)
      fetchReports()
      fetchLowScores()
      fetchStats()
    }
  } catch (e) {
    showToast('评估失败')
  } finally {
    evaluating.value = false
  }
}

function showDetail(report) {
  selectedReport.value = report
  showPopup.value = true
}

function getScoreClass(score) {
  if (score >= 90) return 'score-excellent'
  if (score >= 75) return 'score-good'
  if (score >= 60) return 'score-pass'
  return 'score-fail'
}

function formatTime(time) {
  if (!time) return ''
  return time.replace('T', ' ').substring(0, 16)
}

onMounted(() => {
  fetchStats()
  fetchReports()
  fetchLowScores()
})
</script>

<style scoped>
.evaluation-container {
  min-height: 100vh;
  background: #f5f5f5;
  padding-top: 46px;
}

.content {
  padding: 12px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  margin-bottom: 16px;
}

.stat-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  text-align: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #333;
}

.stat-label {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

.section {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin-bottom: 12px;
}

.metrics-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.metric-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-bar {
  height: 8px;
  background: #f0f0f0;
  border-radius: 4px;
  overflow: hidden;
}

.metric-fill {
  height: 100%;
  background: linear-gradient(90deg, #D4914A, #B8926E);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.metric-info {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #666;
}

.report-card {
  background: #fafafa;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 8px;
  cursor: pointer;
}

.report-card:active {
  background: #f0f0f0;
}

.report-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.report-score {
  font-size: 18px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 4px;
  color: #fff;
}

.score-excellent { background: #52c41a; }
.score-good { background: #1890ff; }
.score-pass { background: #faad14; }
.score-fail { background: #ff4d4f; }

.report-level {
  font-size: 12px;
  color: #666;
  padding: 2px 6px;
  background: #f0f0f0;
  border-radius: 4px;
}

.report-time {
  font-size: 11px;
  color: #999;
  margin-left: auto;
}

.report-query {
  font-size: 14px;
  color: #333;
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.report-diagnosis {
  font-size: 12px;
  color: #999;
  line-height: 1.4;
}

.report-metrics {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #999;
}

.empty {
  text-align: center;
  color: #999;
  padding: 20px;
  font-size: 14px;
}

.detail-popup {
  padding: 20px;
}

.detail-title {
  font-size: 18px;
  font-weight: 700;
  margin-bottom: 16px;
  text-align: center;
}

.detail-label {
  font-size: 13px;
  color: #999;
  margin-bottom: 6px;
}

.detail-query {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.detail-scores {
  margin-bottom: 16px;
}

.score-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f5f5f5;
  font-size: 14px;
}

.score-item.total {
  font-weight: 600;
  border-bottom: none;
  padding-top: 12px;
}

.detail-diagnosis {
  background: #f5f5f5;
  padding: 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
}
</style>
