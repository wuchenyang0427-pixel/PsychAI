<script setup>
// ===== 我的报告页 =====
// 列表 + 详情一体：narrative 用 marked 渲染 Markdown，其余字段渲染成控件
import { ref, computed, onMounted } from 'vue'
import { request } from '@/api/request'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const reports = ref([])
const loading = ref(false)
const activeId = ref(null) // 当前展开的报告

const load = async () => {
  loading.value = true
  try {
    reports.value = await request('/reports')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

// 当前展开的报告对象
const active = computed(() =>
  reports.value.find((r) => r.reportId === activeId.value)
)

// 严重程度 → 标签颜色
const severityType = { 轻度: 'success', 中度: 'warning', 重度: 'danger' }

// narrative 的 Markdown 转 HTML：必须过 DOMPurify 消毒，
// 模型输出属于"外部内容"，直接 v-html 有 XSS 风险
const mdHtml = (text) => DOMPurify.sanitize(marked.parse(text || ''))

const fmtDate = (s) => s || ''
</script>

<template>
  <div class="reports-page">
    <div class="page-head">
      <h2 class="page-title">我的报告</h2>
      <el-button :icon="Refresh" circle :loading="loading" @click="load" />
    </div>

    <!-- 空状态 -->
    <el-empty
      v-if="!loading && reports.length === 0"
      description="还没有报告。和 AI 心理助手聊聊，然后点击「生成我的分析报告」"
    />

    <!-- 报告卡片列表 -->
    <el-card
      v-for="r in reports"
      :key="r.reportId"
      class="report-card"
      :class="{ active: r.reportId === activeId }"
      shadow="hover"
      @click="activeId = activeId === r.reportId ? null : r.reportId"
    >
      <!-- 摘要行：时间 + 等级标签 + 一句话概述 -->
      <div class="row">
        <span class="time">{{ fmtDate(r.createdAt) }}</span>
        <el-tag :type="severityType[r.severity] || 'info'" effect="dark" size="small">
          {{ r.severity }}
        </el-tag>
        <el-tag
          v-if="r.riskFlag"
          type="danger"
          effect="plain"
          size="small"
        >
          存在风险信号
        </el-tag>
        <span class="summary">{{ r.stateSummary }}</span>
      </div>

      <!-- 展开的报告详情 -->
      <template v-if="active && active.reportId === r.reportId">
        <el-divider />
        <div class="detail" @click.stop>
          <!-- 判断依据 -->
          <div class="block">
            <div class="label">严重程度判断依据</div>
            <p class="reason">{{ r.severityReason }}</p>
          </div>

          <!-- 情绪标签 -->
          <div class="block" v-if="r.emotionTags.length">
            <div class="label">情绪标签</div>
            <div class="tags">
              <el-tag v-for="t in r.emotionTags" :key="t" type="info" effect="plain">
                {{ t }}
              </el-tag>
            </div>
          </div>

          <!-- 建议列表 -->
          <div class="block" v-if="r.suggestions.length">
            <div class="label">建议与措施</div>
            <ul class="suggestion-list">
              <li v-for="(s, i) in r.suggestions" :key="i">{{ s }}</li>
            </ul>
          </div>

          <!-- 高风险警示 -->
          <el-alert
            v-if="r.riskFlag"
            type="error"
            :closable="false"
            show-icon
            class="risk-alert"
            title="报告识别到高危风险信号，请尽快联系专业机构或心理援助热线"
          />

          <!-- Markdown 正文 -->
          <div class="block">
            <div class="label">报告全文</div>
            <div class="markdown" v-html="mdHtml(r.narrative)"></div>
          </div>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.reports-page {
  max-width: 860px;
  margin: 0 auto;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-title {
  margin: 0;
  font-size: 20px;
  color: var(--ink-1);
  letter-spacing: 0.5px;
}
.report-card {
  margin-bottom: 14px;
  border-radius: 16px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.report-card.active {
  border-color: var(--brand);
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.time {
  font-size: 13px;
  color: var(--ink-3);
}
.summary {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail {
  cursor: default;
}
.block {
  margin-bottom: 16px;
}
.label {
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 8px;
}
.reason {
  margin: 0;
  font-size: 14px;
  color: var(--ink-1);
  line-height: 1.7;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.suggestion-list {
  margin: 0;
  padding-left: 20px;
  color: var(--ink-1);
  font-size: 14px;
  line-height: 1.9;
}
.risk-alert {
  margin-bottom: 16px;
}

/* Markdown 正文样式（v-html 内容需用 :deep 穿透 scoped） */
.markdown {
  font-size: 14px;
  color: var(--ink-1);
  line-height: 1.8;
}
.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3) {
  font-size: 16px;
  margin: 14px 0 8px;
  color: var(--ink-1);
}
.markdown :deep(p) {
  margin: 8px 0;
}
.markdown :deep(ul),
.markdown :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}
.markdown :deep(code) {
  background: var(--brand-soft);
  color: var(--brand-deep);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}
.markdown :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--brand);
  color: var(--ink-2);
}
</style>
