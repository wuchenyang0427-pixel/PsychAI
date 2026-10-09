// 路由配置
import { createRouter, createWebHistory } from 'vue-router'
import BackendLayout from '@/components/BackendLayout.vue'
import UserLayout from '@/views/user/UserLayout.vue'

// 后台布局路由：4 个菜单页面作为子路由，仅管理员可访问
const backendLayoutRoutes = [
  {
    path: '/backend',
    component: BackendLayout,
    redirect: '/backend/analysis',
    meta: { role: 'admin' },
    children: [
      { path: 'analysis', component: () => import('@/views/DataAnalysis.vue') },
      { path: 'articles', component: () => import('@/views/KnowledgeArticles.vue') },
      { path: 'records', component: () => import('@/views/ConsultationRecords.vue') },
      { path: 'wall', component: () => import('@/views/WallManage.vue') },
    ],
  },
]

// 用户前端布局路由：仅普通用户可访问
const userLayoutRoutes = [
  {
    path: '/user',
    component: UserLayout,
    redirect: '/user/home',
    meta: { role: 'user' },
    children: [
      { path: 'home', component: () => import('@/views/user/UserHome.vue') },
      { path: 'assistant', component: () => import('@/views/user/AiAssistant.vue') },
      { path: 'reports', component: () => import('@/views/user/Reports.vue') },
      { path: 'memory', component: () => import('@/views/user/AiMemory.vue') },
      { path: 'story', component: () => import('@/views/user/StoryWall.vue') },
      { path: 'ended', component: () => import('@/views/user/ChatEnded.vue') },
    ],
  },
]

// 认证布局路由：左侧品牌面板共用，右侧为登录/注册子页面
const authRoutes = [
  {
    path: '/auth',
    component: () => import('@/views/auth/AuthLayout.vue'),
    redirect: '/auth/login',
    children: [
      {
        path: 'login',
        name: 'auth.login',
        component: () => import('@/views/auth/Login.vue'),
      },
      {
        path: 'register',
        name: 'auth.register',
        component: () => import('@/views/auth/Register.vue'),
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/auth/login' },
    // 兼容旧地址 /login
    { path: '/login', redirect: '/auth/login' },
    ...authRoutes,
    ...backendLayoutRoutes,
    ...userLayoutRoutes,
  ],
})

// 各角色登录后的落地页
const homeByRole = {
  admin: '/backend/analysis',
  user: '/user/home',
}

// 全局守卫：未登录去登录页；角色不匹配回自己的首页
router.beforeEach((to) => {
  // 登录/注册页始终放行（保证“统一从认证页进入”）
  if (to.path.startsWith('/auth/')) return true

  const session = JSON.parse(localStorage.getItem('app_user') || 'null')

  // 未登录：记住本来想去的地址，登录后跳回
  if (!session) {
    return { path: '/auth/login', query: { redirect: to.fullPath } }
  }

  // 需要特定角色但当前用户角色不符：回自己角色的首页
  if (to.meta.role && to.meta.role !== session.role) {
    return homeByRole[session.role]
  }

  return true
})

export default router
