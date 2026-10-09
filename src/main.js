import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'

// element-plus 组件由 unplugin 自动按需导入，无需在此 app.use
createApp(App).use(router).mount('#app')
