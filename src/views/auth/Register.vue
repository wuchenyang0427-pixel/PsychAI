<script setup>
// ===== 注册表单（嵌套路由 auth.register）=====
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message as MessageIcon, Iphone, Lock } from '@element-plus/icons-vue'
import { registerUser } from './auth.js'

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  email: '',
  userId: '',
  password: '123456', // 默认密码
})

const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  userId: [
    { required: true, message: '请输入用户ID（手机号）', trigger: 'blur' },
    {
      pattern: /^1[3-9]\d{9}$/,
      message: '请输入正确的手机号',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
}

const onSubmit = async () => {
  await formRef.value.validate()
  loading.value = true

  try {
    // 调后端注册接口，用户数据写入 Redis
    await registerUser(form)
    ElMessage.success('注册成功，请登录')
    // 带上手机号跳到登录页，方便直接输密码
    router.push({ path: '/auth/login', query: { userId: form.userId } })
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="form-box">
    <h2 class="form-title">创建账号</h2>
    <p class="form-sub">注册后即可使用AI心理健康助手</p>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      size="large"
      @keyup.enter="onSubmit"
    >
      <el-form-item prop="email">
        <el-input
          v-model="form.email"
          placeholder="请输入邮箱"
          :prefix-icon="MessageIcon"
        />
      </el-form-item>
      <el-form-item prop="userId">
        <el-input
          v-model="form.userId"
          placeholder="请输入用户ID（手机号）"
          :prefix-icon="Iphone"
          maxlength="11"
        />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          placeholder="请输入密码（默认 123456）"
          :prefix-icon="Lock"
        />
      </el-form-item>
      <el-button
        type="primary"
        class="submit-btn"
        :loading="loading"
        @click="onSubmit"
      >
        注 册
      </el-button>
    </el-form>

    <div class="switch">
      已有账号？
      <router-link to="/auth/login" class="link">返回登录</router-link>
    </div>
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
</style>
