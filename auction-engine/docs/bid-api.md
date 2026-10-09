# 입찰 API 명세 (구현 완료)

## POST /api/bids

입찰을 등록한다.

### 요청

```http
POST /api/bids
Content-Type: application/json

{
  "productId": "11111111-1111-1111-1111-111111111111",
  "bidderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": 15000
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| productId | UUID | 입찰 대상 상품 ID. |
| bidderId | UUID | 입찰자 ID. 이번 MVP엔 별도 인증 체계가 없어 요청 바디로 직접 받는다. |
| amount | integer | 입찰 금액. `현재가(current_price) + 최소 입찰 단위(bid_unit)` 이상이어야 유효하게 처리된다. |

### 응답 - 성공 (201 Created)

```json
{
  "bidId": "b1e0c6d2-...",
  "productId": "3fa85f64-...",
  "bidderId": "3fa85f64-...",
  "amount": 15000,
  "bidAt": "2026-09-23T13:40:00",
  "isValid": true
}
```

⚠️ **"진 입찰"(최저 입찰 단위 미달, 동시 입찰 경쟁에서 밀림)도 항상 201로 응답한다.** `isValid: false`로만 구분한다.
요청 자체는 정상 접수된 것이고(Bid 로그 테이블에 기록됨, README의 "로그 테이블" 방침), 단지 최고가 갱신에는 실패했다는 의미다.
BidService가 진 입찰 기록도 롤백 없이 남기도록 설계돼 있어(트랜잭션 안에서 예외를 던지지 않음), 그 설계와 HTTP 응답 의미를 일치시키기 위해
409가 아닌 201 + `isValid: false`로 통일했다 (마감 연장/최고가 필드는 아직 미구현이라 응답에 없음 — 아래 "미구현" 참고).

### 응답 - 실패

```json
{
  "code": "PRODUCT_NOT_FOUND",
  "message": "상품을 찾을 수 없습니다: ..."
}
```

| code | HTTP status | 설명 |
| --- | --- | --- |
| PRODUCT_NOT_FOUND | 404 | 존재하지 않는 상품 |
| AUCTION_NOT_IN_PROGRESS | 409 | 상품 status가 진행중/마감연장이 아님 (아직 시작 전이거나 이미 종료됨) |
| INVALID_REQUEST | 400 | productId/bidderId 누락, amount 누락/0 이하 등 요청 형식 자체가 잘못됨 |

### 미구현 (다음 단계)

- `extended`(마감 연장 여부), `productEndAt`, `productCurrentPrice` 필드는 안티 스나이핑/마감 연장 로직이 아직 구현되지 않아 응답에 없음.
  최신 현재가는 `GET /api/products`로 별도 조회해야 한다.

## 실시간 반영

입찰 성공 시(`isValid: true`) Redis Pub/Sub 단일 채널 `auction_events`로 `bid_placed` 이벤트를 발행한다. `isValid: false`(진 입찰)는 발행하지 않는다.

B(실시간 중계)가 이 채널 하나만 구독해 모든 이벤트를 받고, 받은 메시지를 그대로(pass-through) 브라우저에 전달하므로 payload에 `event` 필드(예: `"bid_placed"`)가 반드시 포함되어야 이벤트 종류를 구분할 수 있다. 이벤트 스펙은 [README.md](../../README.md)의 "WebSocket 이벤트" 섹션 참고 (`bid_placed`, `auction_extended`, `auction_closed`).
