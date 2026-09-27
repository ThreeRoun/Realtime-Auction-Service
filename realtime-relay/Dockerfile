FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install --production

COPY . .

# 중계 서버 WebSocket 포트
EXPOSE 4000

CMD ["node", "server.js"]