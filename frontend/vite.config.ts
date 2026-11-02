// 使用 Vite 的配置辅助函数，获得配置字段的类型检查和提示。
import { defineConfig } from 'vite'
// 导入 Vue 单文件组件编译插件。
import vue from '@vitejs/plugin-vue'

// 开发环境允许通过环境变量切换后端，默认连接本机 Spring Boot 服务。
const backendTarget = process.env.VITE_API_TARGET || 'http://127.0.0.1:8080'
// CSV 上传与远程 ETL 可能耗时较长，代理两端均允许等待三十五分钟。
const etlProxyTimeout = 2100000

export default defineConfig({
  // 启用 Vue 单文件组件编译；开发服务器将 /api 请求转发至后端以避免跨域。
  plugins: [vue()],
  // 以下配置只作用于开发服务器，不把后端地址打入业务接口路径。
  server: {
    // 浏览器仍访问同源 /api，由开发服务器代为连接后端。
    proxy: {
      '/api': {
        // 代理目标可由环境变量替换，业务代码无需修改请求前缀。
        target: backendTarget,
        // 同时设置客户端等待和代理后端等待，避免长任务中途被代理终止。
        timeout: etlProxyTimeout,
        proxyTimeout: etlProxyTimeout
      }
    }
  }
})
