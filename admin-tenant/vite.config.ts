import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react()],
    server: {
      port: 5175,
      strictPort: true,
      proxy: {
        '/api': {
          target: env.VITE_API_BASE_URL || 'http://localhost:8082',
          changeOrigin: true
        }
      }
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      restoreMocks: true
    }
  }
})
