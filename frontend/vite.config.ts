import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backendTarget = process.env.VITE_API_TARGET || 'http://127.0.0.1:18080'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': backendTarget
    }
  }
})
