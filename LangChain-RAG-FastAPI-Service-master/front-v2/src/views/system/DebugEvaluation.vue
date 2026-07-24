<template>
  <div class="evaluation">
    <!-- 顶部操作栏 -->
    <div class="eval-header">
      <h2>RAG 评估与消融实验</h2>
      <a-space v-if="activeTab === 'overview'">
        <a-button @click="refreshOverview" :loading="overviewLoading">
          <template #icon><icon-refresh /></template>
          刷新概览
        </a-button>
      </a-space>
      <a-space v-else-if="activeTab === 'quality'">
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
      <a-space v-else>
        <a-button type="primary" @click="handleRunAblation" :loading="ablationRunning">
          <template #icon><icon-play-arrow /></template>
          执行全部消融实验
        </a-button>
        <a-button @click="fetchAblationReport" :loading="ablationLoading">
          <template #icon><icon-refresh /></template>
          刷新报告
        </a-button>
      </a-space>
    </div>

    <a-tabs v-model:active-key="activeTab" @change="onTabChange">
      <!-- ==================== Tab 1: 质量概览 ==================== -->
      <a-tab-pane key="overview" title="质量概览">
        <a-row :gutter="16" class="stats-row">
          <a-col :xs="12" :sm="12" :md="6">
            <a-card class="stat-card">
              <a-statistic title="今日查询数" :value="stats.todayTraceCount || 0" />
            </a-card>
          </a-col>
          <a-col :xs="12" :sm="12" :md="6">
            <a-card class="stat-card">
              <a-statistic title="周平均质量分" :value="stats.weekAvgScore ? Number(stats.weekAvgScore.toFixed(0)) : 0">
                <template #suffix>/ 100</template>
              </a-statistic>
            </a-card>
          </a-col>
          <a-col :xs="12" :sm="12" :md="6">
            <a-card class="stat-card">
              <a-statistic title="本周优秀率" :value="excellentRate">
                <template #suffix>%</template>
              </a-statistic>
            </a-card>
          </a-col>
          <a-col :xs="12" :sm="12" :md="6">
            <a-card class="stat-card">
              <a-statistic title="平均响应耗时" :value="stats.todayAvgLatency ? Number((stats.todayAvgLatency / 1000).toFixed(1)) : 0">
                <template #suffix>{{ stats.todayAvgLatency ? 's' : '-' }}</template>
              </a-statistic>
            </a-card>
          </a-col>
        </a-row>

        <a-row :gutter="16" class="content-row">
          <a-col :xs="24" :md="12">
            <a-card title="本周 RAGAS 指标均值" class="section-card">
              <div class="metrics-grid">
                <div class="metric-item">
                  <div class="metric-info">
                    <span>忠实度 (Faithfulness)</span>
                    <span>{{ formatPercent(stats.weekAvgFaithfulness) }}</span>
                  </div>
                  <a-progress :percent="(stats.weekAvgFaithfulness || 0)" :show-text="false" color="#D4914A" size="small" />
                </div>
                <div class="metric-item">
                  <div class="metric-info">
                    <span>答案相关性 (Relevancy)</span>
                    <span>{{ formatPercent(stats.weekAvgRelevancy) }}</span>
                  </div>
                  <a-progress :percent="(stats.weekAvgRelevancy || 0)" :show-text="false" color="#165dff" size="small" />
                </div>
                <div class="metric-item">
                  <div class="metric-info">
                    <span>上下文精度 (Precision)</span>
                    <span>{{ formatPercent(stats.weekAvgPrecision) }}</span>
                  </div>
                  <a-progress :percent="(stats.weekAvgPrecision || 0)" :show-text="false" color="#00b42a" size="small" />
                </div>
                <div class="metric-item">
                  <div class="metric-info">
                    <span>上下文召回 (Recall)</span>
                    <span>{{ formatPercent(stats.weekAvgRecall) }}</span>
                  </div>
                  <a-progress :percent="(stats.weekAvgRecall || 0)" :show-text="false" color="#722ed1" size="small" />
                </div>
              </div>
            </a-card>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-card :title="`近 14 天质量趋势`" class="section-card">
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
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="24">
            <a-card title="近 30 天评级分布" class="section-card">
              <div class="distribution-row">
                <div class="dist-item">
                  <div class="dist-count excellent">{{ distribution.excellent || 0 }}</div>
                  <div class="dist-label">优秀 (≥90)</div>
                </div>
                <div class="dist-item">
                  <div class="dist-count good">{{ distribution.good || 0 }}</div>
                  <div class="dist-label">良好 (75-89)</div>
                </div>
                <div class="dist-item">
                  <div class="dist-count pass">{{ distribution.pass || 0 }}</div>
                  <div class="dist-label">及格 (60-74)</div>
                </div>
                <div class="dist-item">
                  <div class="dist-count fail">{{ distribution.fail || 0 }}</div>
                  <div class="dist-label">不及格 (&lt;60)</div>
                </div>
              </div>
            </a-card>
          </a-col>
        </a-row>
      </a-tab-pane>

      <!-- ==================== Tab 2: 质量评估 ==================== -->
      <a-tab-pane key="quality" title="质量评估">
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
              <a-statistic title="平均耗时" :value="stats.todayAvgLatency ? Number((stats.todayAvgLatency / 1000).toFixed(1)) : 0">
                  <template #suffix>{{ stats.todayAvgLatency ? 's' : '-' }}</template>
                </a-statistic>
            </a-card>
          </a-col>
        </a-row>

        <a-row :gutter="16" class="content-row">
          <!-- 左列 -->
          <a-col :xs="24" :md="16" class="left-col">
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
            <a-card title="评估报告" class="section-card report-card">
              <a-table :data="reports" :pagination="{ pageSize: 15 }" :bordered="false" size="small">
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

            <!-- 测试用例管理 -->
            <a-card class="section-card">
              <template #title>
                <a-space>
                  <span>测试用例（{{ testCases.length }}）</span>
                </a-space>
              </template>
              <template #extra>
                <a-space>
                  <a-button size="small" status="warning" @click="handleDedupTestCases" :loading="deduping">
                    <template #icon><icon-common /></template>
                    去重
                  </a-button>
                </a-space>
              </template>
              <div v-if="testCases.length > 0" class="testcase-list">
                <div v-for="tc in testCases" :key="tc.id" class="testcase-item">
                  <div class="testcase-header">
                    <a-tag size="small" :color="tc.difficulty === 'simple' ? 'green' : tc.difficulty === 'medium' ? 'blue' : 'red'">
                      {{ tc.difficulty || 'simple' }}
                    </a-tag>
                    <a-button type="text" size="mini" status="danger" @click="handleDeleteTestCase(tc.id)">
                      删除
                    </a-button>
                  </div>
                  <div class="testcase-question">{{ tc.question }}</div>
                  <div class="testcase-date">{{ formatDate(tc.createdAt) }}</div>
                </div>
              </div>
              <a-empty v-else description="暂无测试用例，请先生成" />
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
      </a-tab-pane>

      <!-- ==================== Tab 2: 消融实验 ==================== -->
      <a-tab-pane key="ablation" title="消融实验">
        <!-- 消融统计卡片 -->
        <a-row :gutter="16" class="stats-row">
          <a-col :xs="12" :sm="12" :lg="6">
            <a-card class="stat-card">
              <a-statistic title="基线综合得分" :value="baselineScoreNum">
                  <template #suffix>分</template>
                </a-statistic>
            </a-card>
          </a-col>
          <a-col :xs="12" :sm="12" :lg="6">
            <a-card class="stat-card">
              <a-statistic title="实验组数" :value="ablationExperiments.length" />
            </a-card>
          </a-col>
          <a-col :xs="12" :sm="12" :lg="6">
            <a-card class="stat-card">
              <div v-if="mostImpactfulComponent" style="font-size:13px; color: var(--color-text-3);">影响最大组件</div>
              <div v-if="mostImpactfulComponent" style="font-size:16px; font-weight:600;">{{ mostImpactfulComponent }}</div>
              <div v-else style="color: var(--color-text-4);">-</div>
            </a-card>
          </a-col>
          <a-col :xs="12" :sm="12" :lg="6">
            <a-card class="stat-card">
              <div v-if="ablationReport.baseline" style="font-size:13px; color: var(--color-text-3);">测试用例数</div>
              <div v-if="ablationReport.baseline" style="font-size:16px; font-weight:600;">{{ ablationReport.baseline.testCaseCount || '-' }}</div>
              <div v-else style="color: var(--color-text-4);">-</div>
            </a-card>
          </a-col>
        </a-row>

        <!-- 实验总结 -->
        <a-card v-if="ablationReport.summary" title="实验总结" class="section-card">
          <div class="summary-text">{{ ablationReport.summary }}</div>
        </a-card>

        <a-row :gutter="16" class="content-row">
          <!-- 左列：实验结果表格 -->
          <a-col :xs="24" :md="16">
            <a-card :title="`实验结果对比（${ablationExperiments.length} 组）`" class="section-card">
              <div v-if="ablationExperiments.length > 0">
                <a-table :data="ablationExperiments" :pagination="false" :bordered="false" size="small">
                  <template #columns>
                    <a-table-column title="编号" data-index="experimentId" :width="60" />
                    <a-table-column title="实验名称" data-index="experimentName" :width="180" />
                    <a-table-column title="综合得分" :width="90" align="center">
                      <template #cell="{ record }">
                        <span :style="{ color: (record.compositeScore || 0) < 0.5 ? 'var(--color-danger-6)' : 'var(--color-text-1)' }">
                          {{ record.compositeScore != null ? (record.compositeScore * 100).toFixed(1) : '-' }}
                        </span>
                      </template>
                    </a-table-column>
                    <a-table-column title="Δ 得分" :width="90" align="center">
                      <template #cell="{ record }">
                        <span v-if="record.deltaScore != null" :style="{ color: record.deltaScore < -0.01 ? '#f53f3f' : record.deltaScore > 0.01 ? '#00b42a' : 'var(--color-text-1)', fontWeight: 600 }">
                          {{ record.deltaScore > 0 ? '+' : '' }}{{ (record.deltaScore * 100).toFixed(1) }}%
                        </span>
                        <span v-else>-</span>
                      </template>
                    </a-table-column>
                    <a-table-column title="忠实度" :width="80" align="center">
                      <template #cell="{ record }">
                        {{ formatPercent(record.faithfulness) }}
                      </template>
                    </a-table-column>
                    <a-table-column title="相关性" :width="80" align="center">
                      <template #cell="{ record }">
                        {{ formatPercent(record.answerRelevancy) }}
                      </template>
                    </a-table-column>
                    <a-table-column title="精确度" :width="80" align="center">
                      <template #cell="{ record }">
                        {{ formatPercent(record.contextPrecision) }}
                      </template>
                    </a-table-column>
                    <a-table-column title="召回率" :width="80" align="center">
                      <template #cell="{ record }">
                        {{ record.contextRecall != null ? formatPercent(record.contextRecall) : '-' }}
                      </template>
                    </a-table-column>
                    <a-table-column title="关键发现" data-index="keyFindings" :ellipsis="true" :min-width="200">
                      <template #cell="{ record }">
                        <a-tooltip :content="record.keyFindings || ''">
                          <span class="finding-text">{{ record.keyFindings || '-' }}</span>
                        </a-tooltip>
                      </template>
                    </a-table-column>
                  </template>
                </a-table>
              </div>
              <a-empty v-else description="暂无消融实验数据，请先执行实验">
                <a-button type="primary" @click="handleRunAblation" :loading="ablationRunning" style="margin-top: 12px;">
                  执行全部消融实验
                </a-button>
              </a-empty>
            </a-card>
          </a-col>

          <!-- 右列 -->
          <a-col :xs="24" :md="8">
            <!-- 基线 RAGAS 四指标 -->
            <a-card v-if="ablationReport.baseline" title="基线 RAGAS 指标" class="section-card">
              <div class="baseline-metrics">
                <div class="bl-metric">
                  <span class="bl-label">忠实度</span>
                  <a-progress :percent="(ablationReport.baseline.faithfulness || 0)" :show-text="false" color="#D4914A" size="small" />
                  <span class="bl-value">{{ formatPercent(ablationReport.baseline.faithfulness) }}</span>
                </div>
                <div class="bl-metric">
                  <span class="bl-label">答案相关性</span>
                  <a-progress :percent="(ablationReport.baseline.answerRelevancy || 0)" :show-text="false" color="#165dff" size="small" />
                  <span class="bl-value">{{ formatPercent(ablationReport.baseline.answerRelevancy) }}</span>
                </div>
                <div class="bl-metric">
                  <span class="bl-label">上下文精度</span>
                  <a-progress :percent="(ablationReport.baseline.contextPrecision || 0)" :show-text="false" color="#00b42a" size="small" />
                  <span class="bl-value">{{ formatPercent(ablationReport.baseline.contextPrecision) }}</span>
                </div>
                <div class="bl-metric">
                  <span class="bl-label">上下文召回</span>
                  <a-progress :percent="(ablationReport.baseline.contextRecall || 0)" :show-text="false" color="#722ed1" size="small" />
                  <span class="bl-value">{{ formatPercent(ablationReport.baseline.contextRecall) }}</span>
                </div>
              </div>
            </a-card>

            <!-- 影响排名 -->
            <a-card v-if="ablationExperiments.length > 0" title="组件影响排名" class="section-card">
              <div class="impact-list">
                <div v-for="(exp, idx) in ablationExperiments" :key="exp.experimentId" class="impact-item">
                  <div class="impact-rank">{{ idx + 1 }}</div>
                  <div class="impact-info">
                    <div class="impact-name">{{ exp.experimentName }}</div>
                    <div class="impact-delta" :style="{ color: (exp.deltaScore || 0) < -0.01 ? '#f53f3f' : '#00b42a' }">
                      {{ exp.deltaScore != null ? ((exp.deltaScore > 0 ? '+' : '') + (exp.deltaScore * 100).toFixed(1) + '%') : '-' }}
                    </div>
                  </div>
                  <a-progress
                    :percent="Math.abs(exp.deltaScore || 0)"
                    :color="(exp.deltaScore || 0) < 0 ? '#f53f3f' : '#00b42a'"
                    :show-text="false"
                    size="small"
                    :style="{ flex: 1, marginLeft: '12px' }"
                  />
                </div>
              </div>
            </a-card>

            <!-- 实验配置说明 -->
            <a-card title="实验配置说明" class="section-card">
              <div class="config-list">
                <div v-for="cfg in ablationConfigs" :key="cfg.experimentId" class="config-item">
                  <a-tag size="small" :color="cfg.experimentId === 'BASELINE' ? 'green' : 'arcoblue'">
                    {{ cfg.experimentId }}
                  </a-tag>
                  <span class="config-name">{{ cfg.experimentName }}</span>
                  <span class="config-component">{{ cfg.ablationComponent }}</span>
                </div>
              </div>
              <div v-if="ablationConfigs.length === 0" style="color: var(--color-text-4); font-size: 13px;">
                点击"执行全部消融实验"后查看配置
              </div>
            </a-card>

            <!-- 生成时间 -->
            <a-card v-if="ablationReport.generatedAt" class="section-card" title="报告生成时间">
              <div style="font-size: 13px; color: var(--color-text-2);">{{ ablationReport.generatedAt }}</div>
            </a-card>
          </a-col>
        </a-row>
      </a-tab-pane>
    </a-tabs>

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
import { ref, computed, onMounted } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { IconPlayArrow, IconPlus, IconRefresh, IconCommon } from '@arco-design/web-vue/es/icon'
import { evaluationApi } from '@/api'
import dayjs from 'dayjs'

// ========== 标签切换 ==========
const activeTab = ref('overview')
const overviewLoaded = ref(false)
const qualityLoaded = ref(false)
const overviewLoading = ref(false)

function onTabChange(key: string | number) {
  if (typeof key === 'number') key = String(key)
  if (key === 'overview' && !overviewLoaded.value) {
    loadOverview()
  } else if (key === 'quality' && !qualityLoaded.value) {
    loadQuality()
  } else if (key === 'ablation') {
    fetchAblationReport()
    fetchAblationConfigs()
  }
}

const excellentRate = computed(() => {
  const total = (distribution.value.excellent || 0) + (distribution.value.good || 0)
    + (distribution.value.pass || 0) + (distribution.value.fail || 0)
  if (total === 0) return 0
  return Math.round(((distribution.value.excellent || 0) / total) * 100)
})

// ========== 质量评估状态 ==========
const evaluating = ref(false)
const generating = ref(false)
const regressing = ref(false)
const deduping = ref(false)
const stats = ref<any>({})
const reports = ref<any[]>([])
const lowScores = ref<any[]>([])
const testCases = ref<any[]>([])
const trendData = ref<any>({})
const distribution = ref<any>({})
const diagnosisStats = ref<any>({})
const regressionResult = ref<any>(null)

const detailVisible = ref(false)
const selectedReport = ref<any>(null)

// ========== 消融实验状态 ==========
const ablationRunning = ref(false)
const ablationLoading = ref(false)
const ablationReport = ref<any>({})
const ablationConfigs = ref<any[]>([])

// ========== 消融计算属性 ==========
const ablationExperiments = computed(() => {
  return ablationReport.value.experiments || []
})

const baselineScoreNum = computed(() => {
  const baseline = ablationReport.value.baseline
  if (!baseline || baseline.compositeScore == null) return 0
  return Math.round(baseline.compositeScore * 1000) / 10
})

const mostImpactfulComponent = computed(() => {
  const exps = ablationExperiments.value
  if (exps.length === 0) return null
  return exps[0].experimentName || null
})

// ========== 生命周期 ==========
onMounted(() => {
  loadOverview()
})

// ========== 质量概览数据加载（轻量） ==========
async function refreshOverview() {
  overviewLoading.value = true
  await loadOverview()
  overviewLoading.value = false
}

async function loadOverview() {
  await Promise.all([
    fetchStats(),
    fetchTrend(),
    fetchDistribution(),
  ])
  overviewLoaded.value = true
}

// ========== 质量评估数据加载（重量级） ==========
async function loadQuality() {
  await Promise.all([
    fetchReports(),
    fetchLowScores(),
    fetchTestCases(),
    fetchDiagnosisStats(),
  ])
  qualityLoaded.value = true
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

// ========== 消融实验数据加载 ==========
async function fetchAblationReport() {
  ablationLoading.value = true
  try {
    const res: any = await evaluationApi.getAblationReport()
    ablationReport.value = res?.data || res || {}
  } catch (e) {
    console.error('获取消融报告失败', e)
    ablationReport.value = {}
  } finally {
    ablationLoading.value = false
  }
}

async function fetchAblationConfigs() {
  try {
    const res: any = await evaluationApi.getAblationExperiments()
    ablationConfigs.value = res?.data || res || []
  } catch (e) {
    console.error('获取消融配置失败', e)
  }
}

// ========== 操作 ==========
async function handleRunBatch() {
  evaluating.value = true
  try {
    const res: any = await evaluationApi.runBatch(7)
    const data = res?.data || res
    Message.success(`评估完成: ${data.evaluated}/${data.total}`)
    await loadOverview()
    await loadQuality()
  } catch (e) {
    Message.error('评估失败')
  } finally {
    evaluating.value = false
  }
}

async function fetchTestCases() {
  try {
    const res: any = await evaluationApi.getTestCases()
    testCases.value = res?.data || res || []
  } catch (e) {
    console.error('获取测试用例失败', e)
  }
}

async function handleGenerateTestCases() {
  generating.value = true
  try {
    const res: any = await evaluationApi.generateTestCases(10)
    const data = res?.data || res
    Message.success(`生成完成: ${data.generated} 条测试用例`)
    await fetchTestCases()
  } catch (e) {
    Message.error('生成失败')
  } finally {
    generating.value = false
  }
}

async function handleDeleteTestCase(id: number) {
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除该测试用例吗？此操作不可恢复。',
    okText: '确定',
    cancelText: '取消',
    onOk: async () => {
      try {
        await evaluationApi.deleteTestCase(id)
        Message.success('删除成功')
        await fetchTestCases()
      } catch (e) {
        Message.error('删除失败')
      }
    },
  })
}

async function handleDedupTestCases() {
  deduping.value = true
  try {
    const res: any = await evaluationApi.dedupTestCases()
    const data = res?.data || res
    Message.success(`去重完成: 移除 ${data.removed} 条，剩余 ${data.total} 条`)
    await fetchTestCases()
  } catch (e) {
    Message.error('去重失败')
  } finally {
    deduping.value = false
  }
}

async function handleRegression() {
  regressing.value = true
  try {
    const res: any = await evaluationApi.runRegression()
    const data = res?.data || res
    regressionResult.value = data
    Message.success(`回归测试完成: 平均分 ${data.avgScore}`)
    await loadOverview()
    await loadQuality()
  } catch (e) {
    Message.error('回归测试失败')
  } finally {
    regressing.value = false
  }
}

async function handleRunAblation() {
  ablationRunning.value = true
  try {
    const res: any = await evaluationApi.runAblationAll()
    const data = res?.data || res
    Message.success(data.message || '消融实验已在后台启动，完成后刷新查看结果')
    // 提示用户在后台完成后刷新
    setTimeout(() => {
      fetchAblationReport()
    }, 5000)
  } catch (e) {
    Message.error('启动消融实验失败')
  } finally {
    ablationRunning.value = false
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
  align-items: stretch;
}

/* 左列：让评估报告卡片填满剩余高度，与右列底部对齐 */
.left-col {
  display: flex;
  flex-direction: column;
}

.left-col .report-card {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.left-col .report-card :deep(.arco-card-body) {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.left-col .report-card :deep(.arco-table) {
  flex: 1;
}

.section-card {
  margin-bottom: 16px;
}

.report-card :deep(.arco-table-container) {
  border-radius: 4px;
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
  max-height: 250px;
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

/* 测试用例列表 */
.testcase-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 250px;
  overflow-y: auto;
}

.testcase-item {
  padding: 10px;
  background: var(--color-fill-2);
  border-radius: 6px;
  transition: background 0.2s;
}

.testcase-item:hover {
  background: var(--color-fill-3);
}

.testcase-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.testcase-question {
  font-size: 13px;
  color: var(--color-text-1);
  line-height: 1.5;
  margin-bottom: 4px;
}

.testcase-date {
  font-size: 11px;
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

/* ========== 消融实验样式 ========== */

/* 实验总结 */
.summary-text {
  font-size: 14px;
  line-height: 1.8;
  color: var(--color-text-2);
  padding: 4px 0;
}

/* 关键发现文本 */
.finding-text {
  font-size: 12px;
  color: var(--color-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 220px;
}

/* 基线指标 */
.baseline-metrics {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bl-metric {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bl-label {
  font-size: 12px;
  color: var(--color-text-2);
  width: 80px;
  flex-shrink: 0;
}

.bl-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-1);
  width: 40px;
  text-align: right;
  flex-shrink: 0;
}

/* 影响排名 */
.impact-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.impact-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.impact-rank {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--color-fill-3);
  font-size: 11px;
  font-weight: 700;
  color: var(--color-text-2);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.impact-info {
  width: 130px;
  flex-shrink: 0;
}

.impact-name {
  font-size: 12px;
  color: var(--color-text-1);
  line-height: 1.3;
}

.impact-delta {
  font-size: 11px;
  font-weight: 600;
}

/* 实验配置说明 */
.config-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.config-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.config-name {
  font-size: 12px;
  color: var(--color-text-1);
}

.config-component {
  font-size: 11px;
  color: var(--color-text-3);
  margin-left: auto;
}

/* ========== 概览 Tab 评级分布 ========== */
.distribution-row {
  display: flex;
  justify-content: space-around;
  text-align: center;
}

.distribution-row .dist-item {
  padding: 16px 24px;
}

.distribution-row .dist-count {
  font-size: 32px;
  font-weight: 700;
}

.distribution-row .dist-count.excellent { color: #00b42a; }
.distribution-row .dist-count.good { color: #165dff; }
.distribution-row .dist-count.pass { color: #ff7d00; }
.distribution-row .dist-count.fail { color: #f53f3f; }

.distribution-row .dist-label {
  font-size: 12px;
  color: var(--color-text-3);
  margin-top: 4px;
}
</style>
