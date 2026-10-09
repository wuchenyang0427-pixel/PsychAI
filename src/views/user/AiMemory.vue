<script setup>
// ===== AI 记忆页：展示 AI 记住了关于"我"的什么 =====
// 数据全部来自 token 里的 userId 对应的 Redis 键，别人看不到你的
import { ref, onMounted } from 'vue'
import { request } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

const profile = ref(null) // 累积档案
const cards = ref([]) // 最近记忆卡
const openThreads = ref([]) // 未完结话题
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const view = await request('/memory')
    profile.value = view.profile
    cards.value = view.recentCards || []
    openThreads.value = view.openThreads || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

// 清空全部记忆：必须二次确认，删了就找不回来了
const clearing = ref(false)
const clearAll = async () => {
  try {
    await ElMessageBox.confirm(
      '将删除 AI 记住的所有内容（档案、记忆卡、话题），且无法恢复。确定清空吗？',
      '清空全部记忆',
      { type: 'warning', confirmButtonText: '确定清空', cancelButtonText: '再想想' }
    )
  } catch {
    return // 用户点了取消
  }
  clearing.value = true
  try {
    await request('/memory', { method: 'DELETE' })
    ElMessage.success('已清空全部记忆')
    profile.value = null
    cards.value = []
    openThreads.value = []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    clearing.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="memory-page">
    <div class="page-head">
      <h2 class="page-title">AI 的记忆</h2>
      <div class="head-actions">
        <el-button :icon="Refresh" circle :loading="loading" @click="load" />
        <el-button type="danger" plain round :loading="clearing" @click="clearAll">
          清空全部记忆
        </el-button>
      </div>
    </div>

    <!-- 还没有任何记忆 -->
    <el-empty
      v-if="!profile && cards.length === 0"
      description="AI 还没有关于你的记忆。和 AI 心理助手聊聊并生成报告后，它会记住你说过的事"
    />

    <template v-else>
      <!-- 累积档案 -->
      <el-card class="block" shadow="never">
        <template #header><b>累积档案</b></template>
        <div v-if="profile" class="profile-grid">
          <div class="item"><span class="label">称呼</span>{{ profile.nickname || '—' }}</div>
          <div class="item"><span class="label">背景</span>{{ profile.background || '—' }}</div>
          <div class="item wide"><span class="label">当前困扰</span>
            <el-tag v-for="c in profile.concerns || []" :key="c" type="warning" effect="plain">{{ c }}</el-tag>
            <span v-if="!(profile.concerns || []).length" class="none">—</span>
          </div>
          <div class="item wide"><span class="label">重要关系</span>
            <el-tag v-for="r in profile.relations || []" :key="r" effect="plain">{{ r }}</el-tag>
            <span v-if="!(profile.relations || []).length" class="none">—</span>
          </div>
          <div class="item wide"><span class="label">沟通风格</span>{{ profile.style || '—' }}</div>
          <div class="item wide"><span class="label">最近更新</span>{{ profile.updatedAt || '—' }}</div>
        </div>
        <el-empty v-else description="还没有档案" :image-size="60" />
      </el-card>

      <!-- 未完结话题 -->
      <el-card v-if="openThreads.length" class="block" shadow="never">
        <template #header><b>未完结话题（AI 会主动关心）</b></template>
        <ul class="thread-list">
          <li v-for="t in openThreads" :key="t">{{ t }}</li>
        </ul>
      </el-card>

      <!-- 记忆卡列表 -->
      <el-card class="block" shadow="never">
        <template #header><b>最近记忆卡</b></template>
        <el-empty v-if="!cards.length" description="还没有记忆卡" :image-size="60" />
        <div v-for="c in cards" :key="c.cardId" class="card">
          <div class="card-head">
            <span class="time">{{ c.createdAt }}</span>
            <el-tag size="small" :type="c.riskLevel === 'high' ? 'danger' : c.riskLevel === 'medium' ? 'warning' : 'success'">
              风险 {{ c.riskLevel }}
            </el-tag>
            <span class="mood">情绪 {{ c.moodScore }}/10</span>
          </div>
          <p class="summary">{{ c.summary }}</p>
          <div v-if="c.keyEvents.length" class="line"><span class="label">关键事件</span>{{ c.keyEvents.join('；') }}</div>
          <div v-if="c.selfReported.length" class="line"><span class="label">自述</span>{{ c.selfReported.join('；') }}</div>
          <div v-if="c.resolvedThreads.length" class="line resolved">
            <span class="label">已解决</span><el-tag v-for="t in c.resolvedThreads" :key="t" type="success" size="small" effect="plain">{{ t }}</el-tag>
          </div>
          <div v-if="c.openThreads.length" class="line">
            <span class="label">待跟进</span><el-tag v-for="t in c.openThreads" :key="t" size="small" effect="plain">{{ t }}</el-tag>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.memory-page {
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
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.block {
  margin-bottom: 14px;
  border-radius: 16px;
}
.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 20px;
  font-size: 14px;
  color: var(--ink-1);
}
.item {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.item.wide {
  grid-column: 1 / -1;
  align-items: center;
}
.label {
  flex: 0 0 auto;
  color: var(--ink-3);
  font-size: 13px;
  margin-right: 6px;
}
.none {
  color: var(--ink-3);
}
.thread-list {
  margin: 0;
  padding-left: 20px;
  color: var(--ink-1);
  font-size: 14px;
  line-height: 1.9;
}
.card {
  padding: 12px 0;
  border-bottom: 1px solid #eef3ef;
}
.card:last-child {
  border-bottom: none;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.time {
  font-size: 12px;
  color: var(--ink-3);
}
.mood {
  font-size: 12px;
  color: var(--ink-3);
}
.summary {
  margin: 8px 0;
  font-size: 14px;
  color: var(--ink-1);
  line-height: 1.7;
}
.line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-2);
  margin-top: 6px;
}
.line.resolved {
  color: var(--brand);
}
</style>
