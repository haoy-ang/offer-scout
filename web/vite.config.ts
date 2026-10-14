import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // 开发期把 /api 代理到 Spring Boot，前端代码里只写相对路径，
    // 将来换成真实域名也不用改业务代码（这也是生产环境推荐的做法）
    proxy: {
      '/api': {
        target: 'http://localhost:8124',
        changeOrigin: true,
        // SSE 是长连接，不能被压缩或缓冲，否则前端要等整段生成完才收到第一个字
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache, no-transform'
            }
          })
        },
      },
    },
  },
})
