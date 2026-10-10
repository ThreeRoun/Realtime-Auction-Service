# 입찰 API 명세 (구현 완료)

## POST /api/bids

입찰을 등록한다. **인증 필요** — `Authorization: Bearer <로그인으로 받은 토큰>` 헤더가 있어야
한다. 입찰자(bidderId)는 더 이상 body로 받지 않고, 이 토큰에서 추출한다.

### 요청

```http
POST /api/bids
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json

{
  "productId": "11111111-1111-1111-1111-111111111111",
  "amount": 15000
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| productId | UUID | 입찰 대상 상품 ID. |
| amount | integer | 입찰 금액. `현재가(current_price) + 최소 입찰 단위(bid_unit)` 이상이어야 유효하게 처리된다. |

### 응답 - 성공 (201 Created)

```json
{
  "bidId": "b1e0c6d2-...",
  "productId": "3fa85f64-...",
  "bidderId": "3fa85f64-...",
  "amount": 15000,
  "bidAt": "2026-09-23T13:40:00",
  "isValid": true,
  "extended": false
}
```

⚠️ **"진 입찰"(최저 입찰 단위 미달, 동시 입찰 경쟁에서 밀림)도 항상 201로 응답한다.** `isValid: false`로만 구분한다.
요청 자체는 정상 접수된 것이고(Bid 로그 테이블에 기록됨, README의 "로그 테이블" 방침), 단지 최고가 갱신에는 실패했다는 의미다.
BidService가 진 입찰 기록도 롤백 없이 남기도록 설계돼 있어(트랜잭션 안에서 예외를 던지지 않음), 그 설계와 HTTP 응답 의미를 일치시키기 위해
409가 아닌 201 + `isValid: false`로 통일했다.

`extended`는 **이 요청이 지금 막 마감 연장을 발생시켰는지** 여부다. 유효한 입찰이고, 마감까지 30초 이하로 남은 상태였다면 마감을 2분 연장하고 상품 status를 `EXTENDED`로 바꾼 뒤 `true`가 된다 (값은 `BidService.ANTI_SNIPING_WINDOW` / `EXTENSION_DURATION` 상수로 조정 가능). 진 입찰은 가격이 안 바뀌므로 연장 대상이 아니라 항상 `false`.

### 응답 - 실패

```json
{
  "code": "PRODUCT_NOT_FOUND",
  "message": "상품을 찾을 수 없습니다: ..."
}
```

| code | HTTP status | 설명 |
| --- | --- | --- |
| UNAUTHORIZED | 401 | Authorization 헤더가 없거나 토큰이 유효하지 않음(서명 불일치/만료) |
| SELF_BID_NOT_ALLOWED | 403 | 판매자 본인이 자기 상품에 입찰 시도 (자전 거래 방지) |
| PRODUCT_NOT_FOUND | 404 | 존재하지 않는 상품 |
| AUCTION_NOT_IN_PROGRESS | 409 | 상품 status가 진행중/마감연장이 아님 (아직 시작 전이거나 이미 종료됨) |
| INVALID_REQUEST | 400 | productId 누락, amount 누락/0 이하 등 요청 형식 자체가 잘못됨 |

### 미구현 (다음 단계)

- `productEndAt`, `productCurrentPrice` 필드는 응답에 없음. 최신 현재가/마감시각은 `GET /api/products`로 별도 조회해야 한다.

## 실시간 반영

입찰 성공 시(`isValid: true`) Redis Pub/Sub 단일 채널 `auction_events`로 `bid_placed` 이벤트를 발행한다. `isValid: false`(진 입찰)는 발행하지 않는다. 마감이 연장되면(`extended: true`) `auction_extended` 이벤트도 함께 발행하고, 마감 스케줄러가 상품을 닫으면 `auction_closed` 이벤트도 발행한다 (낙찰/유찰 모두, 자세한 건 `auction-closing.md` 참고).

B(실시간 중계)가 이 채널 하나만 구독해 모든 이벤트를 받고, 받은 메시지를 그대로(pass-through) 브라우저에 전달하므로 payload에 `event` 필드(예: `"bid_placed"`)가 반드시 포함되어야 이벤트 종류를 구분할 수 있다. 이벤트 스펙은 [README.md](../../README.md)의 "WebSocket 이벤트" 섹션 참고 (`bid_placed`, `auction_extended`, `auction_closed`).
