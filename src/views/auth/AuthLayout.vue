<script setup>
// 认证布局：左侧绿色品牌面板（登录/注册页共用），右侧渲染子路由表单
</script>

<template>
  <div class="auth-page">
    <!-- ===== 左侧：品牌展示区，两个页面完全一致 ===== -->
    <aside class="brand-panel">
      <div class="brand-logo">
        <span class="brand-mark">心</span>
        <span class="brand-name">AI心理健康助手</span>
      </div>
      <div class="brand-content">
        <h1 class="brand-title">用心倾听<br />温暖同行</h1>
        <p class="brand-desc">专业的心理测评与咨询服务，<br />陪你走过每一段需要支持的时光。</p>
      </div>
      <div class="brand-footer">© 2026 AI心理健康助手</div>
    </aside>

    <!-- ===== 右侧：登录 / 注册表单（嵌套路由渲染在这里） ===== -->
    <main class="form-panel">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.auth-page {
  height: 100vh;
  display: flex;
}

/* 左侧绿色面板：鼠尾草绿渐变 + 两团柔光，像晨雾里的树林 */
.brand-panel {
  width: 42%;
  background:
    radial-gradient(circle at 20% 15%, rgba(255, 255, 255, 0.14), transparent 45%),
    radial-gradient(circle at 85% 85%, rgba(224, 183, 135, 0.18), transparent 50%),
    linear-gradient(160deg, #63a47f 0%, #39684d 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px;
  box-sizing: border-box;
}
.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-mark {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.2);
  display: grid;
  place-items: center;
  font-size: 20px;
}
.brand-name {
  font-size: 18px;
  font-weight: 600;
}
.brand-content {
  margin-bottom: auto;
  margin-top: auto;
}
.brand-title {
  font-size: 38px;
  line-height: 1.4;
  letter-spacing: 3px;
  margin: 0 0 20px;
}
.brand-desc {
  font-size: 15px;
  line-height: 1.9;
  opacity: 0.9;
  margin: 0;
}
.brand-footer {
  font-size: 13px;
  opacity: 0.7;
}

/* 右侧表单区：暖米白底，比纯白更柔 */
.form-panel {
  flex: 1;
  display: grid;
  place-items: center;
  background: #f7f9f5;
}

/* 窄屏时隐藏左侧，只留表单 */
@media (max-width: 800px) {
  .brand-panel {
    display: none;
  }
}
</style>
