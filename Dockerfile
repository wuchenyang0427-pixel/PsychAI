# ============================================================
# 前端镜像：Vite 构建 + Nginx 托管
#   构建上下文是仓库根目录（.dockerignore 已排除 backend/ 等无关文件）
# ============================================================

# ---------- 阶段 1：构建 ----------
FROM node:20-alpine AS build
WORKDIR /build

# 先装依赖，利用层缓存
COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- 阶段 2：运行 ----------
FROM nginx:1.27-alpine

COPY --from=build /build/dist /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
