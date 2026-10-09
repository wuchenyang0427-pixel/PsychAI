import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // 自动按需导入 element-plus 的组件（<el-button> 等），
    // 模板里直接用即可，无需手动 import 和注册
    Components({
      resolvers: [ElementPlusResolver()],
    }),
    // 自动按需导入 element-plus 的 API（ElMessage、ElNotification 等）
    // 代码里直接调用即可，无需手动 import
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  resolve: {
    // @ 指向 src 目录，路径别名
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // vite v8 默认只监听 IPv6 [::1]，浏览器走 IPv4 127.0.0.1 会连不上
    // 设为 true 让 vite 同时监听 IPv4 / IPv6 / 局域网
    host: true,
    proxy: {
      // 前端请求 /api/* 时转发到 Spring Boot 后端的 8080 端口
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
