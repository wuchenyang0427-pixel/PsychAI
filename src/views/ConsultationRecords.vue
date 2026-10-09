<script setup>
// ===== 咨询记录（后台）：人员列表 + 每人的心理调查报告 =====
// 唯一数据源：/api/records（真实 Redis 聚合）。
// 重度人员（最新报告 severity=重度）由后端置顶，前端整行标红。
import { ref, onMounted } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { request } from '@/api/request'
import { ElMessage } from 'element-plus'
import { Search, Refresh, WarningFilled } from '@element-plus/icons-vue'

// ---------- 人员列表 ----------
const loading = ref(false)
const rows = ref([])
const keyword = ref('')
const appliedKeyword = ref('')

// 分页只做展示切片，total 永远等于接口返回的真实条数
const page = ref(1)
const pageSize = ref(10)
const pagedRows = () =>
  rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)

const loadList = async () => {
  loading.value = true
  try {
    const qs = appliedKeyword.value ? `?userId=${encodeURIComponent(appliedKeyword.value)}` : ''
    rows.value = await request(`/records${qs}`)
    page.value = 1
  } catch (e) {
    // 失败只提示，不用假数据兜底
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const onSearch = () => {
  appliedKeyword.value = keyword.value.trim()
  loadList()
}
const onReset = () => {
  keyword.value = ''
  appliedKeyword.value = ''
  loadList()
}

// 重度人员整行标红（置顶排序由后端完成）
const rowClassName = ({ row }) => (row.severe ? 'severe-row' : '')

// 严重程度 → 标签颜色
const severityType = { 轻度: 'success', 中度: 'warning', 重度: 'danger' }

// 毫秒时间戳 → yyyy-MM-dd HH:mm:ss；缺失时显示 -
const fmtTs = (ts) => {
  if (!ts) return '-'
  const d = new Date(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// ---------- 报告详情弹窗 ----------
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const currentUser = ref('')
const reports = ref([])
const activeId = ref(null)

const openReports = async (row) => {
  if (row.reportCount === 0) return
  currentUser.value = row.userId
  reports.value = []
  activeId.value = null
  dialogVisible.value = true
  dialogLoading.value = true
  try {
    reports.value = await request(`/records/${encodeURIComponent(row.userId)}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    dialogLoading.value = false
  }
}

const toggle = (id) => {
  activeId.value = activeId.value === id ? null : id
}

// 模型输出属外部内容，Markdown 渲染必须先过 DOMPurify 消毒
const mdHtml = (text) => DOMPurify.sanitize(marked.parse(text || ''))

onMounted(loadList)
</script>

<template>
  <el-card>
    <template #header>
      <div class="head">
        <span class="card-title">咨询记录</span>
        <el-tag v-if="rows.some((r) => r.severe)" type="danger" effect="light" size="large">
          <el-icon class="tag-icon"><WarningFilled /></el-icon>
          当前有 {{ rows.filter((r) => r.severe).length }} 名重度人员，请优先关注
        </el-tag>
      </div>
    </template>

    <!-- 工具栏：按人员ID查询 -->
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="请输入人员ID精确/模糊查询"
        clearable
        style="width: 260px"
        @keyup.enter="onSearch"
        @clear="onReset"
      />
      <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
      <el-button :icon="Refresh" @click="onReset">重置</el-button>
    </div>

    <!-- 人员表 -->
    <el-table
      v-loading="loading"
      :data="pagedRows()"
      :row-class-name="rowClassName"
      stripe
      style="margin-top: 16px"
    >
      <el-table-column label="人员ID" min-width="150">
        <template #default="{ row }">
          <span class="uid">{{ row.userId }}</span>
          <el-tag v-if="row.severe" type="danger" effect="dark" size="small" class="severe-tag">
            重度
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.email || '-' }}</template>
      </el-table-column>
      <el-table-column label="注册时间" width="180">
        <template #default="{ row }">{{ fmtTs(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="reportCount" label="报告数" width="80" align="center" />
      <el-table-column label="最近报告" width="180">
        <template #default="{ row }">{{ row.latestCreatedAt || '暂无报告' }}</template>
      </el-table-column>
      <el-table-column label="严重程度" width="100" align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.latestSeverity"
            :type="severityType[row.latestSeverity] || 'info'"
            effect="dark"
            size="small"
          >
            {{ row.latestSeverity }}
          </el-tag>
          <el-tag v-else type="info" effect="plain" size="small">未评估</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="风险信号" width="120" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.latestRiskFlag" type="danger" effect="plain" size="small">
            高危
          </el-tag>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="最新状态概述"
        min-width="220"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <span :class="{ muted: !row.latestStateSummary }">
            {{ row.latestStateSummary || '该人员尚未生成报告' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            size="small"
            :disabled="row.reportCount === 0"
            @click="openReports(row)"
          >
            查看报告
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有匹配的人员" />
      </template>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="rows.length"
        :page-size="pageSize"
        v-model:current-page="page"
      />
    </div>

    <!-- 某人员的全部心理调查报告 -->
    <el-dialog
      v-model="dialogVisible"
      :title="`心理调查报告 · ${currentUser}`"
      width="780px"
      top="6vh"
    >
      <div v-loading="dialogLoading" class="report-list">
        <el-empty v-if="!dialogLoading && reports.length === 0" description="该人员暂无报告" />

        <el-card
          v-for="r in reports"
          :key="r.reportId"
          class="report-card"
          :class="{ active: r.reportId === activeId }"
          shadow="never"
          @click="toggle(r.reportId)"
        >
          <div class="r-row">
            <span class="r-time">{{ r.createdAt }}</span>
            <el-tag :type="severityType[r.severity] || 'info'" effect="dark" size="small">
              {{ r.severity }}
            </el-tag>
            <el-tag v-if="r.riskFlag" type="danger" effect="plain" size="small">
              存在风险信号
            </el-tag>
            <span class="r-summary">{{ r.stateSummary }}</span>
          </div>

          <template v-if="activeId === r.reportId">
            <el-divider />
            <div class="detail" @click.stop>
              <div class="block">
                <div class="label">严重程度判断依据</div>
                <p class="reason">{{ r.severityReason }}</p>
              </div>
              <div class="block" v-if="r.emotionTags && r.emotionTags.length">
                <div class="label">情绪标签</div>
                <div class="tags">
                  <el-tag
                    v-for="t in r.emotionTags"
                    :key="t"
                    type="info"
                    effect="plain"
                    size="small"
                  >
                    {{ t }}
                  </el-tag>
                </div>
              </div>
              <div class="block" v-if="r.suggestions && r.suggestions.length">
                <div class="label">建议与措施</div>
                <ul class="suggestion-list">
                  <li v-for="(s, i) in r.suggestions" :key="i">{{ s }}</li>
                </ul>
              </div>
              <el-alert
                v-if="r.riskFlag"
                type="error"
                :closable="false"
                show-icon
                class="risk-alert"
                title="报告识别到高危风险信号，请尽快联系专业机构或心理援助热线"
              />
              <div class="block">
                <div class="label">报告全文</div>
                <div class="markdown" v-html="mdHtml(r.narrative)"></div>
              </div>
            </div>
          </template>
        </el-card>
      </div>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 14px;
}
.card-title {
  font-weight: 600;
}
.tag-icon {
  vertical-align: -2px;
  margin-right: 3px;
}
.toolbar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.uid {
  font-weight: 600;
  color: #303133;
}
.severe-tag {
  margin-left: 8px;
}
.muted {
  color: #a8abb2;
}

/* 重度人员整行浅红底，置顶 + 标红双重视觉提醒 */
:deep(.severe-row) {
  background: #fef0f0 !important;
}
:deep(.el-table__body tr.severe-row:hover > td) {
  background: #fde2e2 !important;
}

/* 报告弹窗 */
.report-list {
  max-height: 68vh;
  overflow-y: auto;
  padding-right: 6px;
}
.report-card {
  margin-bottom: 12px;
  border-radius: 10px;
  cursor: pointer;
  border: 1px solid #ebeef5;
}
.report-card.active {
  border-color: #409eff;
}
.r-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.r-time {
  font-size: 13px;
  color: #909399;
  white-space: nowrap;
}
.r-summary {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #303133;
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
  color: #909399;
  margin-bottom: 8px;
}
.reason {
  margin: 0;
  font-size: 14px;
  color: #303133;
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
  color: #303133;
  font-size: 14px;
  line-height: 1.9;
}
.risk-alert {
  margin-bottom: 16px;
}
.markdown {
  font-size: 14px;
  color: #303133;
  line-height: 1.8;
}
.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3) {
  font-size: 16px;
  margin: 14px 0 8px;
}
.markdown :deep(p) {
  margin: 8px 0;
}
.markdown :deep(ul),
.markdown :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}
.markdown :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #dcdfe6;
  color: #606266;
}
</style>
