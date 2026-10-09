<script setup>
// ===== 登录表单（嵌套路由 auth.login）=====
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { loginUser } from './auth.js'

// 会话同时存 localStorage 的键名（与路由守卫一致）
const SESSION_KEY = 'app_user'

const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  // 注册成功跳回来时通过 query 带上手机号，自动回填
  userId: route.query.userId || '',
  password: '',
})

const rules = {
  userId: [{ required: true, message: '请输入用户ID', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const onSubmit = async () => {
  await formRef.value.validate()
  loading.value = true

  try {
    // 请求后端校验，成功拿到 token 和角色
    const data = await loginUser(form.userId, form.password)

    // 写入会话（含 token，后续接口由 request.js 自动带上）
    localStorage.setItem(
      SESSION_KEY,
      JSON.stringify({ username: data.userId, role: data.role, token: data.token })
    )

    // 每次登录都是新的对话开始：清空该用户的本地聊天记录。
    // 注意：大模型的长期记忆在后端 Redis（profile + memory cards），
    // 每次聊天由 system prompt 注入，不依赖本地历史，所以清空不影响"AI 记得我"。
    localStorage.removeItem(`chat_messages_${data.userId}`)
    const target =
      route.query.redirect ||
      (data.role === 'admin' ? '/backend/analysis' : '/user/home')
    router.push(target)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="form-box">
    <h2 class="form-title">欢迎登录</h2>
    <p class="form-sub">登录后进入对应工作台</p>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      size="large"
      @keyup.enter="onSubmit"
    >
      <el-form-item prop="userId">
        <el-input
          v-model="form.userId"
          placeholder="请输入用户ID（手机号）"
          :prefix-icon="User"
        />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          placeholder="请输入密码"
          :prefix-icon="Lock"
        />
      </el-form-item>
      <el-button
        type="primary"
        class="submit-btn"
        :loading="loading"
        @click="onSubmit"
      >
        登 录
      </el-button>
    </el-form>

    <div class="switch">
      还没有账号？
      <router-link to="/auth/register" class="link">立即注册</router-link>
    </div>

    <!-- 演示账号提示，接后端后删除 -->
    <el-alert type="info" :closable="false" class="tips">
      <template #title>
        演示账号：admin / 123456（管理员），user / 123456（用户）
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
.form-box {
  width: 380px;
  padding: 20px;
}
.form-title {
  margin: 0 0 6px;
  font-size: 26px;
  color: var(--ink-1);
  letter-spacing: 1px;
}
.form-sub {
  margin: 0 0 28px;
  font-size: 14px;
  color: var(--ink-3);
}
.submit-btn {
  width: 100%;
  height: 44px;
  border-radius: 12px;
  font-size: 15px;
  letter-spacing: 4px;
}
.switch {
  margin-top: 18px;
  text-align: center;
  font-size: 14px;
  color: var(--ink-2);
}
.link {
  color: var(--brand-deep);
  text-decoration: none;
  font-weight: 500;
}
.link:hover {
  text-decoration: underline;
}
.tips {
  margin-top: 20px;
}
</style>
