# 상품 API 명세 (구현 완료)

이슈 #10([프론트엔드] 상품 목록 API 연동), 2차 정기회의(9/26) "상품 등록/입찰 API 실제 동작" 요구사항을 반영한 스펙.

## POST /api/products

상품을 등록한다. **인증 필요** — `Authorization: Bearer <로그인으로 받은 토큰>` 헤더가 있어야
한다. 판매자(sellerId)는 더 이상 body로 받지 않고, 이 토큰에서 추출한다.

### 요청

```http
POST /api/products
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json

{
  "title": "무선 헤드폰",
  "description": "상태 좋은 무선 헤드폰입니다.",
  "startingPrice": 30000,
  "bidUnit": 5000,
  "startAt": "2026-09-26T10:00:00",
  "endAt": "2026-09-30T18:00:00"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| startAt | datetime (선택) | 프론트 상품 등록 폼에 시작 시각 입력란이 없어서(등록하면 바로 시작한다고 가정) 선택값. 생략하면 서버가 현재 시각으로 채운다. |

### 응답 - 성공 (201 Created)

Product 객체 하나 (아래 GET 응답과 동일한 모양).

⚠️ **상태 자동 전이 스케줄러가 아직 없어서, 상태는 등록 시점에 한 번만 결정된다:**

- `startAt`을 생략했거나 이미 지났으면(현재 시각 이하) → 바로 `IN_PROGRESS`로 생성 (즉시 입찰 가능)
- `startAt`이 미래면 → `PENDING`으로 생성 (스케줄러가 없으므로 시간이 지나도 자동으로 `IN_PROGRESS`가 되지 않음 — 데모/테스트 시 `startAt`을 생략하거나 과거로 넣을 것)

### 응답 - 실패

| HTTP status | code | 설명 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | Authorization 헤더가 없거나 토큰이 유효하지 않음(서명 불일치/만료) |
| 404 | USER_NOT_FOUND | 토큰의 사용자가 가입 후 삭제되는 등 실제로는 존재하지 않는 경우 |
| 400 | INVALID_REQUEST | title/startingPrice/bidUnit/startAt/endAt 중 필수값 누락, 또는 형식 오류 |
| 400 | (메시지만) | endAt이 startAt보다 앞이거나 같음 |

## POST /api/products/{id}/image

상품 이미지를 업로드한다. 등록(`POST /api/products`)과 별도 엔드포인트로 둔 이유는 등록 요청이
JSON body라서 — 이미지는 상품을 먼저 등록한 뒤 이 엔드포인트로 추가로 올린다.
**인증 필요**, 그리고 **본인이 등록한 상품만** 업로드 가능.

이미지는 S3 같은 외부 스토리지가 아니라 서버(컨테이너) 로컬 디스크에 저장되고,
`/images/<파일명>` 경로로 정적 서빙된다.

### 요청

```http
POST /api/products/3fa85f64-5717-4562-b3fc-2c963f66afa6/image
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: multipart/form-data; boundary=...

--...
Content-Disposition: form-data; name="image"; filename="photo.jpg"
Content-Type: image/jpeg

<바이너리>
--...--
```

허용 형식: `image/jpeg`, `image/png`, `image/webp`. 최대 5MB.

### 응답 - 성공 (200 OK)

Product 객체 하나 (GET 응답과 동일한 모양, `imageUrl`이 새로 채워진 상태).

### 응답 - 실패

| HTTP status | code | 설명 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | Authorization 헤더가 없거나 토큰이 유효하지 않음 |
| 403 | NOT_PRODUCT_OWNER | 본인이 등록한 상품이 아님 |
| 404 | PRODUCT_NOT_FOUND | 존재하지 않는 상품 |
| 400 | (메시지만) | 파일이 비어있거나, jpeg/png/webp가 아닌 형식 |

## GET /api/products

### 쿼리 파라미터

인증 없음(MVP). 쿼리 파라미터 전부 선택.

| 파라미터 | 설명 |
| --- | --- |
| status | 없으면 기본값(`IN_PROGRESS`, `EXTENDED` — 즉 입찰 가능한 진행중 상품)만 반환. `all`이면 전체 상태 반환. `PENDING`/`SOLD`/`UNSOLD`/`COMPLETED` 등 특정 상태 하나만 넘기면 그 상태만 필터링. |

정렬: 마감 임박순(`end_at asc`) 고정. 페이지네이션 없음(MVP).

### 응답 (200 OK)

```json
[
  {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "title": "무선 헤드폰",
    "description": "상태 좋은 무선 헤드폰입니다.",
    "startingPrice": 30000,
    "currentPrice": 45000,
    "bidUnit": 5000,
    "sellerId": "11111111-1111-1111-1111-111111111111",
    "status": "IN_PROGRESS",
    "startAt": "2026-09-26T10:00:00",
    "endAt": "2026-09-30T18:00:00",
    "winnerId": null,
    "createdAt": "2026-09-20T09:00:00",
    "imageUrl": "/images/3f1e2b7a-....jpg"
  }
]
```

`imageUrl`은 이미지를 아직 안 올린 상품이면 `null`. 상품이 없으면 빈 배열 `[]`.

### 응답 - 실패 (GET /api/products)

| HTTP status | 설명 |
| --- | --- |
| 400 | status 파라미터 값이 `ProductStatus` enum 값도 아니고 `all`도 아님 |

### ⚠️ 프론트(C) 연동 시 주의 — 기존 임시 타입과 다른 부분

`frontend/src/data/products.ts`의 임시 `Product` 타입과 실제 응답이 두 가지 다르다:

1. **`id`가 `number`가 아니라 `string`(UUID)이다.** `Number(id)` 같은 변환 코드가 있다면 제거해야 함.
2. **`status` 값이 `"active" | "extended" | "closed"`가 아니라 실제 엔티티 enum 그대로다:**
   `PENDING`(등록대기) / `IN_PROGRESS`(진행중) / `EXTENDED`(마감연장) / `SOLD`(낙찰확정) / `UNSOLD`(유찰) / `COMPLETED`(완료).
   기본 목록 조회(status 파라미터 없음) 결과에는 `IN_PROGRESS`, `EXTENDED`만 나오므로, 목록 화면에서는 사실상 이 두 값만 처리하면 된다.

## GET /api/products/{id}

상품 상세 조회. 응답 모양은 `GET /api/products`의 배열 원소 하나와 동일.

| HTTP status | 설명 |
| --- | --- |
| 200 | Product 객체 하나 |
| 404 | 존재하지 않는 상품 (PRODUCT_NOT_FOUND) |

## GET /api/products/{id}/bids

해당 상품에 대한 입찰 이력 전체 조회. **진 입찰(`isValid: false`)도 포함** — Bid가
로그 테이블 방침으로 설계돼 있어서, 누가 언제 얼마를 불렀는지 전부 보여주기 위함.

정렬: 최신 입찰이 먼저 나오도록 `bid_at desc`.

### 응답 (200 OK)

```json
[
  {
    "bidId": "b1e0c6d2-...",
    "bidderId": "3fa85f64-...",
    "amount": 15000,
    "bidAt": "2026-09-23T13:40:00",
    "isValid": true
  }
]
```

`productId`는 URL에 이미 있으므로 응답에 다시 넣지 않는다. 상품은 존재하는데 입찰이
없으면 빈 배열 `[]`.

| HTTP status | 설명 |
| --- | --- |
| 404 | 존재하지 않는 상품 (PRODUCT_NOT_FOUND) |
