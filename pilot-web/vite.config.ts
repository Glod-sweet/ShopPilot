import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期联调策略：走 Vite 代理，前端代码里只写相对路径 `/api/**`。
 *
 * 为什么用代理而不是让浏览器直连 8080：
 * 1. 同源。浏览器认为请求发往 5173，不会触发 CORS 预检，联调时少一类问题。
 * 2. 代码里不出现 host。部署到 Nginx 后同一条 `/api` 前缀照样成立，不用改前端。
 * 3. cookie / 自定义头在代理下行为更接近生产。
 *
 * 后端已配置 CORS（pilot-server 的 CorsConfig 放行 http://localhost:5173），
 * 所以两种方式都能通。若想直连验证 CORS 是否真的生效，把下面 proxy 注释掉，
 * 并在 src/api/catalog.ts 里把 BASE 改成 'http://localhost:8080/api' 即可。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 前端只认 Java，绝不直接连 Python
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
