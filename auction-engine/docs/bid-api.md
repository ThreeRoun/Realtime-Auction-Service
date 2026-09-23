# 입찰 API 명세 (초안)

로직 구현 전 요청/응답 형식만 먼저 정리한 문서. 동시성 처리(#4)와 함께 실제 구현한다.

## POST /api/products/{productId}/bids

특정 상품에 입찰을 등록한다.

### 요청

```
POST /api/products/{productId}/bids
Content-Type: application/json

{
  "bidderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": 15000
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| bidderId | UUID | 입찰자 ID. 이번 MVP엔 별도 인증 체계가 없어 요청 바디로 직접 받는다. |
| amount | integer | 입찰 금액. `현재가(current_price) + 최소 입찰 단위(bid_unit)` 이상이어야 한다. |

### 응답 - 성공 (201 Created)

```json
{
  "bidId": "b1e0c6d2-...",
  "productId": "3fa85f64-...",
  "bidderId": "3fa85f64-...",
  "amount": 15000,
  "bidAt": "2026-09-23T13:40:00",
  "productCurrentPrice": 15000,
  "productEndAt": "2026-09-23T14:00:00",
  "extended": false
}
```

| 필드 | 설명 |
| --- | --- |
| extended | 이 입찰로 인해 마감 시각이 연장됐는지 여부 (안티 스나이핑, #4에서 구현) |
| productEndAt | 연장 여부와 무관하게 응답 시점 기준 최종 마감 시각 |

### 응답 - 실패

공통 에러 포맷:

```json
{
  "code": "BID_TOO_LOW",
  "message": "최소 입찰 단위 이상이어야 합니다.",
  "currentPrice": 14000,
  "minNextBid": 15000
}
```

| code | HTTP status | 설명 |
| --- | --- | --- |
| PRODUCT_NOT_FOUND | 404 | 존재하지 않는 상품 |
| AUCTION_NOT_IN_PROGRESS | 409 | 상품 status가 진행중/마감연장이 아님 (아직 시작 전이거나 이미 종료됨) |
| BID_TOO_LOW | 400 | amount가 `current_price + bid_unit` 미만 |
| BID_LOST_CONCURRENT | 409 | 동시 입찰 중 락 경쟁에서 밀림 (요청 자체는 Bid 로그로 남지만 is_valid=false) |

동시 입찰 시 이긴 요청만 201로 성공하고, 진 요청은 `BID_LOST_CONCURRENT`로 응답한다 — 두 요청 모두 `bids` 테이블엔 기록되고 `is_valid`만 다르다(README의 "로그 테이블" 방침).

## 실시간 반영

입찰 성공 시(`BID_LOST_CONCURRENT` 제외) Redis Pub/Sub `bid_placed` 이벤트를 발행한다. `extended: true`인 경우 `auction_extended` 이벤트도 함께 발행한다. 이벤트 스펙은 [README.md](../../README.md)의 "WebSocket 이벤트" 섹션 참고 (`bid_placed`, `auction_extended`, `auction_closed`).
