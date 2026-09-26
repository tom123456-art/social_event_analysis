// 前端应用入口：创建 Vue 应用并注册路由、状态与组件库。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { ElMessage } from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/main.css'
import App from './App.vue'
import router from './router'

// 兜底展示遗漏处理的异步异常，避免点击操作失败后页面没有任何反馈。
window.addEventListener('unhandledrejection', (event) => {
  const message = event.reason instanceof Error && /[\u4e00-\u9fff]/.test(event.reason.message)
    ? event.reason.message
    : '操作失败，请稍后重试。'
  ElMessage.error(message)
  event.preventDefault()
})

// 未预期的页面脚本错误不直接暴露技术细节，统一给出可理解的中文提示。
window.addEventListener('error', (event) => {
  if (event.error) ElMessage.error('页面处理出现异常，请刷新后重试。')
})

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
