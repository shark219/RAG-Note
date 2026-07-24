<template>
  <div class="evaluation-container">
    <van-nav-bar title="RAG 质量评估" fixed left-arrow @click-left="$router.back()" />

    <div class="content">
      <!-- 统计卡片 -->
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-value">{{ stats.todayTraceCount || 0 }}</div>
          <div class="stat-label">今日查询</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ stats.weekAvgScore ? stats.weekAvgScore.toFixed(0) : '-' }}</div>
          <div class="stat-label">周平均分</div>
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

      <!-- 四指标进度条 -->
      <div class="section">
        <div class="section-title">四指标均值（本周）</div>
        <div class="metrics-grid">
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill faithfulness" :style="{ width: (stats.weekAvgFaithfulness || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>忠实度</span>
              <span>{{ formatPercent(stats.weekAvgFaithfulness) }}</span>
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill relevancy" :style="{ width: (stats.weekAvgRelevancy || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>答案相关性</span>
              <span>{{ formatPercent(stats.weekAvgRelevancy) }}</span>
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill precision" :style="{ width: (stats.weekAvgPrecision || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>上下文精度</span>
              <span>{{ formatPercent(stats.weekAvgPrecision) }}</span>
            </div>
          </div>
          <div class="metric-item">
            <div class="metric-bar">
              <div class="metric-fill recall" :style="{ width: (stats.weekAvgRecall || 0) * 100 + '%' }"></div>
            </div>
            <div class="metric-info">
              <span>上下文召回</span>
              <span>{{ formatPercent(stats.weekAvgRecall) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 趋势图（简化版：最近 7 天分数列表） -->
      <div class="section">
        <div class="section-title">趋势（最近 {{ trendData.dates?.length || 0 }} 天）</div>
        <div v-if="trendData.dates && trendData.dates.length > 0" class="trend-chart">
          <div v-for="(date, i) in trendData.dates" :key="date" class="trend-row">
            <span class="trend-date">{{ date.substring(5) }}</span>
            <div class="trend-bar-container">
              <div class="trend-bar" :style="{ width: (trendData.totalScore?.[i] || 0) + '%' }"></div>
            </div>
            <span class="trend-value">{{ trendData.totalScore?.[i]?.toFixed(0) || '-' }}</span>
          </div>
        </div>
        <div v-else class="empty">暂无趋势数据</div>
      </div>

      <!-- 评级分布 -->
      <div class="section">
        <div class="section-title">评级分布（最近 30 天）</div>
        <div class="distribution-grid">
          <div class="dist-item">
            <div class="dist-count">{{ distribution.excellent || 0 }}</div>
            <div class="dist-label excellent">优秀</div>
          </div>
          <div class="dist-item">
            <div class="dist-count">{{ distribution.good || 0 }}</div>
            <div class="dist-label good">良好</div>
          </div>
          <div class="dist-item">
            <div class="dist-count">{{ distribution.pass || 0 }}</div>
            <div class="dist-label pass">及格</div>
          </div>
          <div class="dist-item">
            <div class="dist-count">{{ distribution.fail || 0 }}</div>
            <div class="dist-label fail">不及格</div>
          </div>
        </div>
      </div>

      <!-- 问题类型分布 -->
      <div class="section">
        <div class="section-title">问题类型（最近 30 天）</div>
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
      </div>

      <!-- 操作按钮 -->
      <div class="section">
        <div class="btn-row">
          <van-button type="primary" size="small" @click="triggerBatchEvaluate" :loading="evaluating">
            批量评估
          </van-button>
          <van-button type="success" size="small" @click="generateTestCases" :loading="generating">
            生成测试用例
          </van-button>
          <van-button type="warning" size="small" @click="runRegression" :loading="regressing">
            回归测试
          </van-button>
        </div>
      </div>

      <!-- 回归测试结果 -->
      <div v-if="regressionResult" class="section">
        <div class="section-title">回归测试结果</div>
        <div class="regression-summary">
          <div class="reg-item"><span>用例数</span><span>{{ regressionResult.total }}</span></div>
          <div class="reg-item"><span>成功率</span><span>{{ regressionResult.success }}/{{ regressionResult.total }}</span></div>
          <div class="reg-item"><span>平均分</span><span :class="getScoreClass(regressionResult.avgScore)">{{ regressionResult.avgScore }}</span></div>
          <div class="reg-item"><span>忠实度</span><span>{{ (regressionResult.avgFaithfulness * 100).toFixed(0) }}%</span></div>
          <div class="reg-item"><span>相关性</span><span>{{ (regressionResult.avgRelevancy * 100).toFixed(0) }}%</span></div>
        </div>
      </div>

      <!-- 低分样本列表 -->
      <div class="section">
        <div class="section-title">低分样本（{{ lowScores.length }}）</div>
        <div v-if="lowScores.length === 0" class="empty">暂无低分样本</div>
        <div v-for="item in lowScores" :key="item.id" class="report-card" @click="showDetail(item)">
          <div class="report-header">
            <span class="report-score" :class="getScoreClass(item.totalScore)">{{ item.totalScore }}</span>
            <span class="report-level">{{ item.level }}</span>
            <span v-if="item.harmonicMean" class="report-harmonic">H={{ item.harmonicMean.toFixed(2) }}</span>
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
            <span v-if="item.harmonicMean" class="report-harmonic">H={{ item.harmonicMean.toFixed(2) }}</span>
            <span class="report-time">{{ formatTime(item.createdAt) }}</span>
          </div>
          <div class="report-query">{{ item.query }}</div>
          <div class="report-metrics">
            <span>忠实: {{ formatPercent(item.faithfulness) }}</span>
            <span>相关: {{ formatPercent(item.answerRelevancy) }}</span>
            <span>精度: {{ formatPercent(item.contextPrecision) }}</span>
            <span>召回: {{ formatPercent(item.contextRecall) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <van-popup v-model:show="showPopup" position="bottom" round :style="{ height: '75%' }">
      <div class="detail-popup" v-if="selectedReport">
        <div class="detail-title">评估详情</div>
        <div class="detail-query">
          <div class="detail-label">用户问题</div>
          <div>{{ selectedReport.query }}</div>
        </div>
        <div class="detail-scores">
          <div class="score-item">
            <span>忠实度（Faithfulness）</span>
            <span>{{ formatPercent(selectedReport.faithfulness) }}</span>
          </div>
          <div class="score-item">
            <span>答案相关性（Answer Relevancy）</span>
            <span>{{ formatPercent(selectedReport.answerRelevancy) }}</span>
          </div>
          <div class="score-item">
            <span>上下文精度（Context Precision）</span>
            <span>{{ formatPercent(selectedReport.contextPrecision) }}</span>
          </div>
          <div class="score-item">
            <span>上下文召回（Context Recall）</span>
            <span>{{ selectedReport.contextRecall ? formatPercent(selectedReport.contextRecall) : '无标准答案' }}</span>
          </div>
          <div class="score-item">
            <span>规则评分</span>
            <span>{{ selectedReport.ruleScore || '-' }}</span>
          </div>
          <div class="score-item">
            <span>调和平均数</span>
            <span>{{ selectedReport.harmonicMean ? selectedReport.harmonicMean.toFixed(3) : '-' }}</span>
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

const token = ref(localStorage.getItem('jwt_token') || '')

const stats = ref({})
const reports = ref([])
const lowScores = ref([])
const trendData = ref({})
const distribution = ref({})
const diagnosisStats = ref({})
const regressionResult = ref(null)
const evaluating = ref(false)
const generating = ref(false)
const regressing = ref(false)
const showPopup = ref(false)
const selectedReport = ref(null)

function getHeaders() {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token.value}`
  }
}

function formatPercent(val) {
  if (val == null) return '-'
  return (val * 100).toFixed(0) + '%'
}

// ========== 数据加载 ==========

async function fetchStats() {
  try {
    const res = await fetch('/evaluation/stats', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) stats.value = json.data
  } catch (e) { console.error('获取统计失败:', e) }
}

async function fetchReports() {
  try {
    const res = await fetch('/evaluation/reports', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) reports.value = json.data || []
  } catch (e) { console.error('获取报告失败:', e) }
}

async function fetchLowScores() {
  try {
    const res = await fetch('/evaluation/low-scores', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) lowScores.value = json.data || []
  } catch (e) { console.error('获取低分样本失败:', e) }
}

async function fetchTrend() {
  try {
    const res = await fetch('/evaluation/trend?days=14', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) trendData.value = json.data
  } catch (e) { console.error('获取趋势失败:', e) }
}

async function fetchDistribution() {
  try {
    const res = await fetch('/evaluation/distribution?days=30', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) distribution.value = json.data
  } catch (e) { console.error('获取分布失败:', e) }
}

async function fetchDiagnosisStats() {
  try {
    const res = await fetch('/evaluation/diagnosis-stats?days=30', { headers: getHeaders() })
    const json = await res.json()
    if (json.code === 200) diagnosisStats.value = json.data
  } catch (e) { console.error('获取诊断分布失败:', e) }
}

// ========== 操作 ==========

async function triggerBatchEvaluate() {
  evaluating.value = true
  try {
    const res = await fetch('/evaluation/batch?days=7', {
      method: 'POST', headers: getHeaders()
    })
    const json = await res.json()
    if (json.code === 200) {
      showToast(`评估完成: ${json.data.evaluated}/${json.data.total}`)
      refreshAll()
    }
  } catch (e) { showToast('评估失败') }
  finally { evaluating.value = false }
}

async function generateTestCases() {
  generating.value = true
  try {
    const res = await fetch('/evaluation/test-cases/generate?count=10', {
      method: 'POST', headers: getHeaders()
    })
    const json = await res.json()
    if (json.code === 200) {
      showToast(`生成完成: ${json.data.generated} 条`)
    }
  } catch (e) { showToast('生成失败') }
  finally { generating.value = false }
}

async function runRegression() {
  regressing.value = true
  try {
    const res = await fetch('/evaluation/regression', {
      method: 'POST', headers: getHeaders()
    })
    const json = await res.json()
    if (json.code === 200) {
      regressionResult.value = json.data
      showToast(`回归测试完成: 平均分 ${json.data.avgScore}`)
      refreshAll()
    }
  } catch (e) { showToast('回归测试失败') }
  finally { regressing.value = false }
}

// ========== 辅助 ==========

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

function refreshAll() {
  fetchStats()
  fetchReports()
  fetchLowScores()
  fetchTrend()
  fetchDistribution()
  fetchDiagnosisStats()
}

onMounted(() => { refreshAll() })
</script>

<style scoped>
.evaluation-container {
  min-height: 100vh;
  background: #f5f5f5;
  padding-top: 46px;
}
.content { padding: 12px; }

/* 统计卡片 */
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
.stat-value { font-size: 24px; font-weight: 700; color: #333; }
.stat-label { font-size: 12px; color: #999; margin-top: 4px; }

/* Section */
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

/* 四指标进度条 */
.metrics-grid { display: flex; flex-direction: column; gap: 12px; }
.metric-item { display: flex; flex-direction: column; gap: 4px; }
.metric-bar { height: 8px; background: #f0f0f0; border-radius: 4px; overflow: hidden; }
.metric-fill { height: 100%; border-radius: 4px; transition: width 0.3s ease; }
.metric-fill.faithfulness { background: #D4914A; }
.metric-fill.relevancy { background: #1890ff; }
.metric-fill.precision { background: #52c41a; }
.metric-fill.recall { background: #722ed1; }
.metric-info { display: flex; justify-content: space-between; font-size: 12px; color: #666; }

/* 趋势图 */
.trend-chart { display: flex; flex-direction: column; gap: 6px; }
.trend-row { display: flex; align-items: center; gap: 8px; }
.trend-date { font-size: 12px; color: #999; width: 40px; text-align: right; }
.trend-bar-container { flex: 1; height: 16px; background: #f0f0f0; border-radius: 4px; overflow: hidden; }
.trend-bar { height: 100%; background: linear-gradient(90deg, #D4914A, #B8926E); border-radius: 4px; transition: width 0.3s; }
.trend-value { font-size: 12px; color: #666; width: 30px; }

/* 评级分布 */
.distribution-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; text-align: center; }
.dist-item { padding: 8px; }
.dist-count { font-size: 20px; font-weight: 700; }
.dist-label { font-size: 12px; margin-top: 4px; }
.dist-label.excellent { color: #52c41a; }
.dist-label.good { color: #1890ff; }
.dist-label.pass { color: #faad14; }
.dist-label.fail { color: #ff4d4f; }

/* 问题类型 */
.diagnosis-grid { display: flex; flex-wrap: wrap; gap: 12px; }
.diag-item { display: flex; flex-direction: column; align-items: center; min-width: 60px; }
.diag-count { font-size: 18px; font-weight: 600; color: #333; }
.diag-label { font-size: 11px; color: #999; }

/* 按钮行 */
.btn-row { display: flex; gap: 8px; }
.btn-row .van-button { flex: 1; }

/* 回归测试结果 */
.regression-summary { display: flex; flex-direction: column; gap: 8px; }
.reg-item { display: flex; justify-content: space-between; font-size: 14px; padding: 4px 0; border-bottom: 1px solid #f5f5f5; }

/* 报告卡片 */
.report-card { background: #fafafa; border-radius: 8px; padding: 12px; margin-bottom: 8px; cursor: pointer; }
.report-card:active { background: #f0f0f0; }
.report-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.report-score { font-size: 18px; font-weight: 700; padding: 2px 8px; border-radius: 4px; color: #fff; }
.score-excellent { background: #52c41a; }
.score-good { background: #1890ff; }
.score-pass { background: #faad14; }
.score-fail { background: #ff4d4f; }
.report-level { font-size: 12px; color: #666; padding: 2px 6px; background: #f0f0f0; border-radius: 4px; }
.report-harmonic { font-size: 11px; color: #999; padding: 2px 6px; background: #e8e8e8; border-radius: 4px; }
.report-time { font-size: 11px; color: #999; margin-left: auto; }
.report-query { font-size: 14px; color: #333; margin-bottom: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.report-diagnosis { font-size: 12px; color: #999; line-height: 1.4; }
.report-metrics { display: flex; gap: 12px; font-size: 11px; color: #999; }

.empty { text-align: center; color: #999; padding: 20px; font-size: 14px; }

/* 详情弹窗 */
.detail-popup { padding: 20px; overflow-y: auto; max-height: 100%; }
.detail-title { font-size: 18px; font-weight: 700; margin-bottom: 16px; text-align: center; }
.detail-label { font-size: 13px; color: #999; margin-bottom: 6px; }
.detail-query { margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid #f0f0f0; }
.detail-scores { margin-bottom: 16px; }
.score-item { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #f5f5f5; font-size: 14px; }
.score-item.total { font-weight: 600; border-bottom: none; padding-top: 12px; }
.detail-diagnosis { background: #f5f5f5; padding: 12px; border-radius: 8px; font-size: 13px; line-height: 1.6; }
</style>
