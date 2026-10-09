// ===== 认证接口封装：对接 Spring Boot 后端（数据存 Redis）=====
import { request } from '@/api/request'

// 注册：成功无返回；失败（如手机号已注册）会抛出带提示语的 Error
export async function registerUser({ email, userId, password }) {
  await request('/auth/register', {
    method: 'POST',
    body: { email, userId, password },
  })
}

// 登录：成功返回 { token, userId, role }
export async function loginUser(userId, password) {
  return request('/auth/login', {
    method: 'POST',
    body: { userId, password },
  })
}

// 登出：通知后端删除 Redis 里的会话令牌；网络失败也不阻断前端退出
export async function logoutUser() {
  try {
    await request('/auth/logout', { method: 'POST' })
  } catch {
    // 忽略：本地会话照样清掉
  }
}
