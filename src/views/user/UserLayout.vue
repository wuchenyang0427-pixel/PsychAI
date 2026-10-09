<script setup>
// ===== 1. 用户前端布局：顶部水平导航 + 内容区 =====
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logoutUser } from '@/views/auth/auth.js'

const route = useRoute()
const router = useRouter()

// 顶部导航配置：新增前端页面时在此加一项 + 路由表 children 加一条
const navs = [
  { path: '/user/home', title: '首页' },
  { path: '/user/assistant', title: 'AI 心理助手' },
  { path: '/user/reports', title: '我的报告' },
  { path: '/user/memory', title: 'AI 的记忆' },
  { path: '/user/story', title: '故事集' },
]

const username = computed(
  () => JSON.parse(localStorage.getItem('app_user') || '{}').username || '用户'
)

const logout = async () => {
  await logoutUser()
  localStorage.removeItem('app_user')
  router.push('/auth/login')
}
</script>

<template>
  <el-container class="layout">
    <!-- ===== 2. 顶部导航栏 ===== -->
    <el-header class="header">
      <div class="brand" @click="router.push('/user/home')">
        <span class="brand-mark">心</span>
        <span class="brand-name">AI心理健康助手</span>
      </div>

      <!-- 水平菜单：router 模式，index 即跳转路径 -->
      <el-menu
        :default-active="route.path"
        mode="horizontal"
        router
        class="nav"
      >
        <el-menu-item v-for="n in navs" :key="n.path" :index="n.path">
          {{ n.title }}
        </el-menu-item>
      </el-menu>

      <el-dropdown @command="(c) => c === 'logout' && logout()">
        <span class="user">
          <el-avatar :size="30">{{ username.charAt(0) }}</el-avatar>
          <span class="user-name">{{ username }}</span>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>

    <!-- ===== 3. 页面内容区（首页是全屏沉浸背景，不要内边距和限宽） ===== -->
    <el-main class="main" :class="{ 'main-full': route.path === '/user/home' }">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}
/* 顶栏：半透明白 + 毛玻璃模糊，滚过长内容时依然轻盈 */
.header {
  height: 60px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 100;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  white-space: nowrap;
}
.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--brand-gradient);
  box-shadow: 0 4px 12px rgba(57, 104, 77, 0.28);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 16px;
}
.brand-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-1);
  letter-spacing: 0.5px;
}
/* 菜单：透明底融进顶栏，选中态由全局 primary 变量接管成绿色 */
.nav {
  flex: 1;
  border-bottom: none;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: var(--brand-soft);
  --el-menu-active-color: var(--brand-deep);
  --el-menu-text-color: var(--ink-2);
}
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
  white-space: nowrap;
}
.user-name {
  font-size: 14px;
  color: var(--ink-2);
}
.main {
  background: var(--bg-page);
  padding: 24px;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
/* 首页：通栏全屏，让飘落背景铺满整个内容区 */
.main-full {
  padding: 0;
  max-width: none;
}
</style>
