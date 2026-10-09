<script setup>
// 用户首页：心声飘落背景 + 玻璃拟态入口
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ChatDotRound, Document } from '@element-plus/icons-vue'
import FallingVoices from '@/components/FallingVoices.vue'

const router = useRouter()
const user = JSON.parse(localStorage.getItem('app_user') || '{}')
const username = computed(() => user.username || '')
</script>

<template>
  <div class="home">
    <!-- 背景层：故事集同款心声飘落（纯展示，不吃鼠标） -->
    <FallingVoices />

    <!-- 前景内容 -->
    <div class="home-content">
      <h2 class="banner-title">你好，{{ username }}</h2>
      <p class="banner-sub">关注心理健康，从今天开始</p>

      <div class="entries">
        <!-- AI 心理助手入口 -->
        <div class="ai-entry" @click="router.push('/user/assistant')">
          <div class="ai-icon">
            <el-icon :size="26"><ChatDotRound /></el-icon>
          </div>
          <div class="ai-text">
            <div class="ai-title">AI 心理助手</div>
            <div class="ai-desc">24 小时在线，倾听你的心事，陪你梳理情绪</div>
          </div>
        </div>

        <!-- 我的报告入口 -->
        <div class="ai-entry" @click="router.push('/user/reports')">
          <div class="ai-icon report-icon">
            <el-icon :size="26"><Document /></el-icon>
          </div>
          <div class="ai-text">
            <div class="ai-title">我的报告</div>
            <div class="ai-desc">查看 AI 为你生成的心理分析报告与专业建议</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 深色沉浸容器：通栏铺满整个内容区（顶栏之下），无圆角无边距 */
.home {
  position: relative;
  height: calc(100vh - 60px); /* 视口高 - 顶栏 */
  overflow: hidden;
}

/* 前景压在背景层之上 */
.home-content {
  position: relative;
  z-index: 10;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 0 24px;
}
.banner-title {
  margin: 0 0 10px;
  font-size: 32px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #f0f7f1;
  text-shadow: 0 2px 18px rgba(0, 0, 0, 0.4);
}
.banner-sub {
  margin: 0;
  font-size: 15px;
  letter-spacing: 2px;
  color: rgba(190, 220, 200, 0.75);
  text-shadow: 0 1px 10px rgba(0, 0, 0, 0.4);
}

/* 两张玻璃拟态入口卡片 */
.entries {
  display: flex;
  gap: 18px;
  margin-top: 44px;
  flex-wrap: wrap;
  justify-content: center;
}
.ai-entry {
  width: 330px;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 22px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.16);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  cursor: pointer;
  text-align: left;
  transition: transform 0.2s, background 0.2s, border-color 0.2s;
}
.ai-entry:hover {
  transform: translateY(-3px);
  background: rgba(255, 255, 255, 0.13);
  border-color: rgba(255, 255, 255, 0.3);
}
.ai-icon {
  flex: 0 0 auto;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--brand-gradient);
  box-shadow: 0 8px 20px rgba(57, 104, 77, 0.35);
  color: #fff;
  display: grid;
  place-items: center;
}
/* 报告入口用暖杏色：与主绿形成冷暖对照 */
.report-icon {
  background: linear-gradient(135deg, #e0b787, #c89a64);
  box-shadow: 0 8px 20px rgba(200, 154, 100, 0.35);
}
.ai-text {
  flex: 1;
  min-width: 0;
}
.ai-title {
  font-size: 17px;
  font-weight: 600;
  color: #f0f7f1;
  margin-bottom: 5px;
}
.ai-desc {
  font-size: 13px;
  color: rgba(190, 220, 200, 0.7);
  line-height: 1.5;
}
</style>
