// 导入深度合并工具，覆盖局部配置时保留基础配置中的其他字段。
import { mergeConfig } from 'vite'
// 复用 Vue 插件和长请求超时等公共开发配置。
import config from './vite.config'

// 复用基础构建配置，仅将本地调试的接口代理切换到 18123 端口。
export default mergeConfig(config, {
  // 仅替换 /api 的后端地址，其余代理选项继续继承基础配置。
  server: { proxy: { '/api': { target: 'http://127.0.0.1:18123' } } }
})
