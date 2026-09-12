import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      // 自动读取 tsconfig.json 中的 paths
      tsconfigPaths: true,
    },
    server: {
      port: 8000,
      host: true,
      open: true,
      proxy: {
        '/apis': {
          target: env.VITE_API_BASE_URL || 'http://localhost:2000',
          changeOrigin: true,
        },
        // 后端返回的缩略图等相对地址走 /api 前缀，dev 下同样转发
        '/api': {
          target: env.VITE_API_BASE_URL || 'http://localhost:2000',
          changeOrigin: true,
        },
        '/dav': {
          target: env.VITE_API_BASE_URL || 'http://localhost:2000',
          changeOrigin: true,
        },
      },
    },
  }
})
