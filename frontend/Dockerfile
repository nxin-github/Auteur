# Frontend Dockerfile — multi-stage
# Stage 1: node 20 build (vue-tsc + vite build → dist/)
# Stage 2: nginx alpine serve dist + 反代 /api → backend:8082

# ──────────────────────────────────────────
# Stage 1: build
# ──────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /workspace

# 先拷 lock 让 npm 缓存依赖
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

# 拷源码 + 配置 + 构建
COPY . .
RUN npm run build

# ──────────────────────────────────────────
# Stage 2: runtime
# ──────────────────────────────────────────
FROM nginx:1.27-alpine

# 自定义 nginx：单页路由 fallback + /api 反代到 backend
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
