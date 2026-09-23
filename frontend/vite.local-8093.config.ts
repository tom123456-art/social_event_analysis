import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const etlProxyTimeout = 2100000

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18123',
        timeout: etlProxyTimeout,
        proxyTimeout: etlProxyTimeout
      }
    }
  }
})
