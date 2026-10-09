<script setup>
// ===== 结束今天的聊天：温馨收尾页 =====
// 点"结束今天的聊天"后，记忆已在后端落库，对话已清空，落到这个页面。
// 以后想做的"温暖话语飘落墙"就加在这个页面的背景层。
import { useRouter } from 'vue-router'
import { MoonNight, ChatDotRound, HomeFilled } from '@element-plus/icons-vue'

const router = useRouter()
const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long',
})
</script>

<template>
  <div class="ended-page">
    <el-icon class="moon" :size="64"><MoonNight /></el-icon>
    <h2 class="title">今天就先聊到这里吧</h2>
    <p class="date">{{ today }}</p>
    <p class="desc">
      你说的话我都好好记住了。<br />
      好好休息，下次来我们可以接着聊。
    </p>
    <div class="actions">
      <el-button type="primary" round :icon="ChatDotRound" @click="router.push('/user/assistant')">
        再聊一会
      </el-button>
      <el-button round :icon="HomeFilled" @click="router.push('/user/home')">
        返回首页
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.ended-page {
  max-width: 560px;
  margin: 0 auto;
  padding-top: 14vh;
  text-align: center;
}
/* 月亮用暖杏色：像夜里留的一盏灯，比冷紫色更有安全感 */
.moon {
  color: #e0b787;
  margin-bottom: 20px;
}
.title {
  margin: 0 0 6px;
  font-size: 24px;
  color: var(--ink-1);
  letter-spacing: 1px;
}
.date {
  margin: 0 0 24px;
  font-size: 13px;
  color: var(--ink-3);
}
.desc {
  margin: 0 0 32px;
  font-size: 15px;
  color: var(--ink-2);
  line-height: 2;
}
.actions {
  display: flex;
  justify-content: center;
  gap: 14px;
}
</style>
