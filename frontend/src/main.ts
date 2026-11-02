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
  // 从未处理的 Promise 异常提取中文提示，并阻止浏览器继续显示默认拒绝事件。
  const message = getErrorMessage(event.reason, '操作失败，请稍后重试。')
  ElMessage.error(message)
  event.preventDefault()
})

// 未预期的页面脚本错误不直接暴露技术细节，统一给出可理解的中文提示。
window.addEventListener('error', (event) => {
  // 尺寸观察器的循环提示不打断用户，其他真实脚本错误统一展示友好消息。
  if (event.message?.includes('ResizeObserver')) return
  if (event.error) ElMessage.error('页面处理出现异常，请刷新后重试。')
})

// 安装路由与中文组件库前注册 Vue 错误处理，统一承接组件运行异常。
const app = createApp(App)
app.config.errorHandler = (error) => {
  // 开发控制台保留原异常，页面只显示经过过滤的中文信息。
  console.error('页面处理失败', error)
  ElMessage.error(getErrorMessage(error, '页面处理出现异常，请刷新后重试。'))
}
// 安装路由与中文 Element Plus 组件库，最后挂载到 HTML 的 app 节点。
app.use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
