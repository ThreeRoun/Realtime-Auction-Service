# 📡 Realtime Relay Engine (실시간 중계 엔진)

경매 상품별 접속자를 방(Room) 단위로 격리 관리하고, 경매 엔진(A)에서 Redis Pub/Sub으로 발행된 입찰 이벤트를 해당 방의 모든 웹소켓 클라이언트(C)에게 실시간으로 브로드캐스트하는 Node.js 기반 중계 서버입니다.

---

## 🛠 Tech Stack

- **Runtime:** Node.js (v20+)
- **WebSocket:** `ws` (RFC 6455 표준 순수 WebSocket)
- **Pub/Sub Client:** `ioredis`

---

## 🔌 WebSocket Connection Specification

### 1. 접속 엔드포인트 (Client → Server)

- **URL:** `ws://{HOST}:{PORT}?productId={productId}`
- **로컬 테스트 주소:** `ws://localhost:4000?productId={상품 UUID}`
- **Query Parameter:**
  - `productId` (필수): 입장하려는 경매 상품의 ID (UUID)
  - 누락 시 서버가 `1008` 코드로 연결을 종료합니다.

### 2. 접속 성공 응답 (Server → Client)

소켓 연결 수립 즉시 전달되는 최초 메시지입니다.

```json
{
  "event": "connected",
  "message": "{productId}번 경매 방 접속 성공"
}
```

---

## 📦 Broadcast Event Specification (Server → Client)

경매 엔진(A)이 Redis `auction_events` 채널로 발행한 메시지를, 중계 서버가 해당 `product_id` 방의 클라이언트들에게 **원문 그대로(pass-through)** 전달합니다. 이벤트 종류는 `event` 필드로 구분합니다.

> 시각 필드는 타임존 정보가 없는 ISO-8601 형식(`LocalDateTime`)입니다. 예: `2026-10-07T12:00:00`

### 1. `bid_placed` (입찰 성공 시)

```json
{
  "event": "bid_placed",
  "product_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "current_price": 75000,
  "bidder_id": "11111111-1111-1111-1111-111111111111",
  "bid_at": "2026-10-07T12:00:00"
}
```

### 2. `auction_extended` (마감 연장 시)

안티 스나이핑(마감 직전 입찰)으로 마감 시각이 연장되었을 때 발생합니다.

```json
{
  "event": "auction_extended",
  "product_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "new_end_at": "2026-10-07T12:05:00"
}
```

### 3. `auction_closed` (마감 확정 시)

유찰된 경우 `winner_id`와 `final_price`는 `null`일 수 있습니다.

```json
{
  "event": "auction_closed",
  "product_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "winner_id": "11111111-1111-1111-1111-111111111111",
  "final_price": 75000
}
```

---

## 🚀 Local Run (로컬 실행 방법)

### 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `PORT` | `4000` | WebSocket 서버 포트 |
| `REDIS_HOST` | `auction-redis` | Redis 호스트 (컨테이너 환경 기준) |
| `REDIS_PORT` | `6379` | Redis 포트 |

### 실행 방법

```bash
# 1. 프로젝트 루트에서 Redis 컨테이너 기동
docker compose up -d redis

# 2. 의존성 설치
cd realtime-relay && npm install

# 3. 서버 구동 (로컬 실행 시 REDIS_HOST 지정 필수)
REDIS_HOST=localhost node server.js
```

`✅ [Redis] 'auction_events' 채널 구독 완료!` 로그가 보이면 정상입니다.

### 단독 동작 확인

```bash
# 터미널 A: 클라이언트 접속
npx wscat -c "ws://localhost:4000?productId=test-room"

# 터미널 B: 테스트 이벤트 발행 → (integer) 1 이 나와야 정상
docker exec -it auction-redis redis-cli PUBLISH auction_events \
  '{"event":"bid_placed","product_id":"test-room","current_price":55000,"bidder_id":"tester","bid_at":"2026-10-07T12:00:00"}'
```

### ⚠️ Troubleshooting

- **PUBLISH 결과가 `(integer) 0`이고 이벤트가 오지 않음**
  Mac에 Homebrew 등으로 설치한 Redis가 켜져 있으면, `localhost:6379` 연결이 도커 Redis가 아닌 로컬 Redis로 갑니다. `lsof -nP -iTCP:6379 -sTCP:LISTEN`으로 `redis-server`가 보이면 `brew services stop redis`로 끈 뒤 relay(와 엔진)를 재시작하세요.
- **`❌ [Redis 연결 오류]`가 반복됨**
  `REDIS_HOST=localhost` 없이 실행한 경우입니다. 기본값 `auction-redis`는 컨테이너 네트워크에서만 접근 가능합니다.
- **`EADDRINUSE` (포트 사용 중)**
  `docker compose up`으로 띄운 `realtime-relay` 컨테이너가 4000 포트를 쓰고 있을 수 있습니다. `docker compose stop realtime-relay` 후 다시 실행하세요.
