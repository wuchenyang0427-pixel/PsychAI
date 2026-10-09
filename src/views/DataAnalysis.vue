<script setup>
// ===== 数据分析页（后台）=====
// 所有数字与图表均来自后端 /stats/overview 对 Redis 的真实聚合，
// 前端不保留任何模拟数据；接口失败时明确报错，不回填假数据。
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { request } from '@/api/request'
import { ElMessage } from 'element-plus'

// ---------- 1. 页面状态：真实数据 + 整体加载态 ----------
const stats = ref(null)
const loading = ref(true)

// KPI 卡片：数值直接取接口字段；平均情绪无数据时显示"—"
const kpis = computed(() => {
  const k = stats.value?.kpi
  return [
    { key: 'users', label: '用户/咨询者总数', value: k?.userCount ?? '—', gradient: ['#8a6cff', '#6a4fe8'] },
    { key: 'likes', label: '点赞/收藏总数', value: k?.likeCount ?? '—', gradient: ['#ff8aa3', '#f4587a'] },
    { key: 'sessions', label: '咨询会话数', value: k?.sessionCount ?? '—', gradient: ['#4fd0e8', '#1fb6d4'] },
    {
      key: 'score',
      label: '总体用户情绪平均数',
      value: k?.avgMood == null ? '—' : k.avgMood,
      suffix: k?.avgMood == null ? '' : '/10',
      gradient: ['#5ed7c5', '#2bb8a5'],
    },
  ]
})

// 摘要四格：同样全部来自接口
const summaries = computed(() => {
  const s = stats.value?.summary
  return [
    { label: '心声总数', value: s?.stories ?? '—', unit: '条' },
    { label: '本周新增用户', value: s?.newUsersThisWeek ?? '—', unit: '人' },
    { label: '已生成报告', value: s?.reports ?? '—', unit: '份' },
    { label: '高危预警', value: s?.highRisk ?? '—', unit: '次' },
  ]
})

// ---------- 2. ECharts 实例管理 ----------
const chartMood = ref(null)
const chartSession = ref(null)
const chartActive = ref(null)
const instances = []

// 通用柔和配色
const C = { purple: '#7b5cff', pink: '#f4587a', cyan: '#1fb6d4', teal: '#2bb8a5',
  axis: '#909399', split: '#eef0f6', text: '#303133' }

const baseAxis = {
  axisLine: { lineStyle: { color: '#dcdfe6' } },
  axisTick: { show: false },
  axisLabel: { color: C.axis, fontSize: 11 },
}

// 折线：平均情绪趋势（数值由接口给；当天无数据为 null，断点不连线）
const renderMood = () => {
  const t = stats.value.moodTrend
  const c = echarts.init(chartMood.value)
  c.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 10, right: 16, top: 30, bottom: 8, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: t.dates, ...baseAxis },
    yAxis: { type: 'value', min: 0, max: 10, splitLine: { lineStyle: { color: C.split } },
      axisLabel: { color: C.axis, fontSize: 11 } },
    series: [{
      name: '平均情绪值', type: 'line', smooth: true, data: t.values,
      connectNulls: false,
      symbolSize: 6, lineStyle: { width: 3, color: C.purple },
      itemStyle: { color: C.purple, borderColor: '#fff', borderWidth: 2 },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(123,92,255,0.28)' },
          { offset: 1, color: 'rgba(123,92,255,0.02)' },
        ]),
      },
    }],
  })
  instances.push(c)
}

// 柱状：咨询会话统计（近 7 天每日记忆卡数）
const renderSession = () => {
  const t = stats.value.sessionTrend
  const c = echarts.init(chartSession.value)
  c.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 10, right: 12, top: 24, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: t.dates, ...baseAxis },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: C.split } },
      axisLabel: { color: C.axis, fontSize: 11 } },
    series: [{
      name: '会话数', type: 'bar', data: t.values, barWidth: '46%',
      itemStyle: {
        borderRadius: [6, 6, 0, 0],
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: '#4fd0e8' },
          { offset: 1, color: '#1fb6d4' },
        ]),
      },
    }],
  })
  instances.push(c)
}

// 面积：用户活跃度趋势（近 21 天每日去重活跃用户数）
const renderActive = () => {
  const t = stats.value.activityTrend
  const c = echarts.init(chartActive.value)
  c.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 10, right: 16, top: 26, bottom: 8, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: t.dates, ...baseAxis },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: C.split } },
      axisLabel: { color: C.axis, fontSize: 11 } },
    series: [{
      name: '活跃用户数', type: 'line', smooth: true, data: t.values,
      symbol: 'circle', symbolSize: 6, lineStyle: { width: 3, color: C.teal },
      itemStyle: { color: C.teal, borderColor: '#fff', borderWidth: 2 },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(43,184,165,0.26)' },
          { offset: 1, color: 'rgba(43,184,165,0.02)' },
        ]),
      },
    }],
  })
  instances.push(c)
}

const onResize = () => instances.forEach((c) => c.resize())

onMounted(async () => {
  try {
    // 先拉真实统计，拿到数据后再初始化图表（避免空容器渲染失败）
    stats.value = await request('/stats/overview')
    await nextTick()
    renderMood()
    renderSession()
    renderActive()
    window.addEventListener('resize', onResize)
  } catch (e) {
    // 明确报错，不使用任何模拟数据兜底
    ElMessage.error('统计数据加载失败：' + e.message)
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  instances.forEach((c) => c.dispose())
})
</script>

<template>
  <div v-loading="loading" element-loading-background="rgba(245,247,252,0.8)" class="analysis-page">
    <template v-if="stats">
      <!-- ===== KPI 卡片区 ===== -->
      <div class="kpi-row">
        <div v-for="k in kpis" :key="k.key" class="kpi-card">
          <div class="kpi-icon" :style="{ background: `linear-gradient(135deg, ${k.gradient[0]}, ${k.gradient[1]})` }">
            <!-- 双人 -->
            <svg v-if="k.key === 'users'" viewBox="0 0 24 24" width="22" height="22">
              <path fill="currentColor" d="M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-3.3 0-7 1.7-7 4.5V20h10v-2.5c0-1 .4-1.9 1-2.7-1.2-.5-2.6-.8-4-.8zm7.5-2a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zm.3 2c-.4 0-.8 0-1.2.1 1 1 1.4 2.1 1.4 3.4V20H22v-2.5c0-2.8-3.4-4.5-5.2-4.5z"/>
            </svg>
            <!-- 爱心 -->
            <svg v-else-if="k.key === 'likes'" viewBox="0 0 24 24" width="22" height="22">
              <path fill="currentColor" d="M12 21s-7.5-4.9-10-9.2C.4 8.6 2 5 5.5 5c2 0 3.4 1.1 4.5 2.6C11.1 6.1 12.5 5 14.5 5 18 5 19.6 8.6 22 11.8 19.5 16.1 12 21 12 21z"/>
            </svg>
            <!-- 对话气泡 -->
            <svg v-else-if="k.key === 'sessions'" viewBox="0 0 24 24" width="22" height="22">
              <path fill="currentColor" d="M4 4h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9l-5 4v-4H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm3 5v2h10V9H7zm0 4v2h7v-2H7z"/>
            </svg>
            <!-- 笑脸 -->
            <svg v-else viewBox="0 0 24 24" width="22" height="22">
              <path fill="currentColor" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-3.5 7a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM12 18c-2.4 0-4.5-1.6-5.2-3.8h10.4C16.5 16.4 14.4 18 12 18z"/>
            </svg>
          </div>
          <div class="kpi-meta">
            <div class="kpi-value">
              {{ k.value }}<span v-if="k.suffix" class="kpi-suffix">{{ k.suffix }}</span>
            </div>
            <div class="kpi-label">{{ k.label }}</div>
          </div>
        </div>
      </div>

      <!-- ===== 2×2 等大图表网格 ===== -->
      <div class="main-grid">
        <!-- 平均情绪趋势分析 -->
        <div class="panel">
          <div class="panel-title">平均情绪趋势分析</div>
          <div ref="chartMood" class="chart"></div>
        </div>

        <!-- 咨询会话统计 -->
        <div class="panel">
          <div class="panel-title">咨询会话统计</div>
          <div ref="chartSession" class="chart"></div>
        </div>

        <!-- 用户活跃度趋势 -->
        <div class="panel">
          <div class="panel-title">用户活跃度趋势</div>
          <div ref="chartActive" class="chart"></div>
        </div>

        <!-- 核心指标摘要 -->
        <div class="panel">
          <div class="panel-title">核心指标摘要</div>
          <div class="summary-grid">
            <div v-for="s in summaries" :key="s.label" class="summary-item">
              <span class="s-value">{{ s.value }}<em>{{ s.unit }}</em></span>
              <span class="s-label">{{ s.label }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.analysis-page {
  min-height: 300px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ===== KPI 卡片 ===== */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.kpi-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px 22px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 2px 12px rgba(31, 45, 80, 0.05);
  transition: transform 0.2s, box-shadow 0.2s;
}
.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(31, 45, 80, 0.1);
}
.kpi-icon {
  flex: none;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  color: #fff;
  box-shadow: 0 6px 14px rgba(0, 0, 0, 0.12);
}
.kpi-value {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}
.kpi-suffix {
  font-size: 14px;
  font-weight: 400;
  color: #909399;
  margin-left: 2px;
}
.kpi-label {
  margin-top: 4px;
  font-size: 13px;
  color: #909399;
}

/* ===== 面板通用 ===== */
.panel {
  background: #fff;
  border-radius: 14px;
  padding: 16px 18px 12px;
  box-shadow: 0 2px 12px rgba(31, 45, 80, 0.05);
  /* 图表坐标标签不能被裁掉（经验：overflow hidden 会吃掉 axisLabel/legend） */
  overflow: visible;
  display: flex;
  flex-direction: column;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
  padding-left: 10px;
  position: relative;
}
.panel-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 3px;
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: linear-gradient(180deg, #8a6cff, #6a4fe8);
}

/* ===== 2×2 等大网格：两列等宽，同行自动等高 ===== */
.main-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

/* 四个图表容器等高：ECharts 必须有明确尺寸才不会初始化空白 */
.chart {
  width: 100%;
  height: 300px;
}

/* ===== 核心指标摘要：填满面板剩余高度，与左侧图表块等大 ===== */
.summary-grid {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 4px 0 8px;
}
.summary-item {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  padding: 10px 16px;
  background: #f7f8fc;
  border-radius: 10px;
}
.s-value {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}
.s-value em {
  font-style: normal;
  font-size: 12px;
  font-weight: 400;
  color: #909399;
  margin-left: 3px;
}
.s-label {
  font-size: 12px;
  color: #909399;
}

/* ===== 窄屏自适应：全部堆叠成一列 ===== */
@media (max-width: 1100px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .main-grid {
    grid-template-columns: 1fr;
  }
}
</style>
