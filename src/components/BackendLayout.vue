<script setup>
// ===== 1. 依赖与菜单数据 =====
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  PieChart,
  Document,
  Message,
  CollectionTag,
} from '@element-plus/icons-vue'
import Navbar from './Navbar.vue'
import { logoutUser } from '@/views/auth/auth.js'

const route = useRoute()
const router = useRouter()

// 侧边菜单折叠状态
const isCollapse = ref(false)
const toggleSidebar = () => {
  isCollapse.value = !isCollapse.value
}

// 菜单配置：新增页面时只需在此加一项 + 路由表加一条
const menus = [
  { path: '/backend/analysis', title: '数据分析', icon: PieChart },
  { path: '/backend/articles', title: '知识文章', icon: Document },
  { path: '/backend/records', title: '咨询记录', icon: Message },
  { path: '/backend/wall', title: '故事集管理', icon: CollectionTag },
]

// 顶栏标题跟随当前菜单高亮项
const currentTitle = computed(
  () => menus.find((m) => m.path === route.path)?.title ?? ''
)

// 退出登录：通知后端删除 Redis 会话，再清本地并回登录页
const handleCommand = async (command) => {
  if (command === 'logout') {
    await logoutUser()
    localStorage.removeItem('app_user')
    router.push('/auth/login')
  }
}
</script>

<template>
  <!-- ===== 2. 整体布局：左侧菜单 + 右侧（顶栏/内容区） ===== -->
  <el-container class="layout">
    <!-- 折叠时侧边栏宽度收窄到 64px，只显示图标 -->
    <el-aside :width="isCollapse ? '64px' : '220px'" class="aside">
      <div class="logo">
        <span class="logo-mark">心</span>
        <span v-show="!isCollapse" class="logo-text">心理咨询后台</span>
      </div>

      <!-- collapse：菜单折叠成图标条；router 模式：index 即路由路径 -->
      <el-menu
        :default-active="route.path"
        :collapse="isCollapse"
        router
        class="menu"
      >
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- ===== 3. 顶栏：最左折叠按钮 + 当前页标题 + 管理员 ===== -->
      <el-header class="header">
        <div class="header-left">
          <Navbar :is-collapse="isCollapse" @toggle="toggleSidebar" />
          <span class="header-title">{{ currentTitle }}</span>
        </div>

        <el-dropdown @command="handleCommand">
          <span class="user">
            <el-avatar :size="30">管</el-avatar>
            <span class="user-name">管理员</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <!-- ===== 4. 内容区：子路由页面渲染在这里 ===== -->
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

/* 左侧菜单：宽度变化时平滑过渡 */
.aside {
  background: #fff;
  border-right: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  transition: width 0.25s;
  overflow: hidden;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid #f0f0f0;
  white-space: nowrap;
}
.logo-mark {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #409eff;
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 16px;
  flex-shrink: 0;
}
.logo-text {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.menu {
  border-right: none;
  padding: 12px 8px;
}
.menu .el-menu-item {
  border-radius: 8px;
  margin-bottom: 4px;
  height: 48px;
}
/* 折叠态下不要右侧内边距，让图标居中 */
.menu.el-menu--collapse {
  padding: 12px 4px;
}

/* 顶栏 */
.header {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.header-left {
  display: flex;
  align-items: center;
}
.header-title {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
.user-name {
  font-size: 14px;
  color: #606266;
}

/* 内容区 */
.main {
  background: #f5f7fa;
  padding: 20px;
}
</style>
