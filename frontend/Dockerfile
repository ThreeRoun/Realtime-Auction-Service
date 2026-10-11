# 1단계: React + Vite 빌드
FROM node:24-alpine AS build

WORKDIR /app

# 의존성 설치
COPY package*.json ./
RUN npm ci

# 소스 복사 및 빌드
COPY . .
RUN npm run build

# 2단계: Nginx로 정적 파일 제공
FROM nginx:alpine

# 빌드된 프론트엔드 파일 복사
COPY --from=build /app/dist /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

# Nginx 기본 HTTP 포트
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
