import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发期通过代理转发到后端，避免跨域配置
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true
      }
    }
  }
})
