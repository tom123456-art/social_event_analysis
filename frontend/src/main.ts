import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { getErrorMessage } from './api/client'
import 'element-plus/dist/index.css'
import './styles/main.css'
import App from './App.vue'
import router from './router'

// 兜底展示遗漏处理的异步异常，避免点击操作失败后页面没有任何反馈。
window.addEventListener('unhandledrejection', (event) => {
  const message = getErrorMessage(event.reason, '操作失败，请稍后重试。')
  ElMessage.error(message)
  event.preventDefault()
})

// 未预期的页面脚本错误不直接暴露技术细节，统一给出可理解的中文提示。
window.addEventListener('error', (event) => {
  if (event.message?.includes('ResizeObserver')) return
  if (event.error) ElMessage.error('页面处理出现异常，请刷新后重试。')
})

const app = createApp(App)
app.config.errorHandler = (error) => {
  console.error('页面处理失败', error)
  ElMessage.error(getErrorMessage(error, '页面处理出现异常，请刷新后重试。'))
}
app.use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
