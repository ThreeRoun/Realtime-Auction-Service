Markdown
# 📡 Realtime Relay Engine (실시간 중계 엔진)

경매 상품별 접속자를 방(Room) 단위로 격리 관리하고, 경매 엔진(A)에서 Redis Pub/Sub으로 발행된 입찰 이벤트를 해당 방의 모든 웹소켓 클라이언트(C)에게 실시간으로 브로드캐스트하는 Node.js 기반 중계 서버입니다.

---

## 🛠 Tech Stack
- **Runtime:** Node.js (v20+)
- **WebSocket:** `ws` (RFC 6455 표준 고성능 순수 WebSocket)
- **Pub/Sub Client:** `ioredis`
- **Architecture:** Monorepo (`realtime-relay`)

---

## 🔌 WebSocket Connection Specification

### 1. 접속 엔드포인트 (Client -> Server)
- **URL:** `ws://{HOST}:{PORT}?productId={productId}`
- **로컬 테스트 주소:** `ws://localhost:4000?productId=101`
- **Query Parameter:**
  - `productId` (필수): 입장하려는 경매 상품의 고유 ID

### 2. 접속 성공 핸드셰이크 응답 (Server -> Client)
소켓 연결 수립 즉시 클라이언트로 전달되는 최초 환영 메시지입니다.
```json
{
  "event": "connected",
  "message": "101번 경매 방 접속 성공"
}
📦 Broadcast Event Specification (Server -> Client)
경매 엔진(Backend A)이 Redis auction_events 채널로 메시지를 발행(Publish)하면, 중계 엔진이 이를 구독(Subscribe)하여 해당 productId 방에 접속 중인 클라이언트들에게 아래 형식으로 전달합니다.
1. bid_placed (새 입찰 등록 성공 시)
새로운 최고가 입찰이 확정되었을 때 발생합니다.
JSON
{
  "event": "bid_placed",
  "product_id": "101",
  "current_price": 75000,
  "bidder_id": "user_42",
  "bid_at": "2026-09-26T19:00:00Z"
}
2. auction_extended (마감 시간 연장 시)
안티 스나이핑 로직(마감 직전 입찰)으로 인해 경매 마감 시각이 연장되었을 때 발생합니다.
JSON
{
  "event": "auction_extended",
  "product_id": "101",
  "new_end_at": "2026-09-26T19:05:00Z"
}
3. auction_closed (경매 마감 확정 시)
경매 시간이 만료되어 최종 낙찰자가 결정되었을 때 발생합니다.
JSON
{
  "event": "auction_closed",
  "product_id": "101",
  "winner_id": "user_42",
  "final_price": 75000
}
🚀 Local Run (로컬 실행 방법)
사전 요구사항
Redis 서버가 구동 중이어야 합니다 (localhost:6379).
실행 명령어
Bash
# 의존성 설치
npm install

# 서버 구동 (기본 포트: 8080)
node server.js