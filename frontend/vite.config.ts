import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 后端 Spring Boot 默认 8082；同源代理避免 CORS
//
// base 路径:本地 dev / Docker / Vercel 都走根路径 / ;GitHub Pages 部署到
// nxin-github.github.io/Auteur/ 子路径,build 时通过 VITE_BASE_PATH 环境变量传 /Auteur/。
// 主路径以 import.meta.env.BASE_URL 形式被 vue-router 读到,保持单一来源。
export default defineConfig({
  base: process.env.VITE_BASE_PATH || '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})
