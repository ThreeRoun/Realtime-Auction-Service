# 상품 목록 조회 API 명세 (구현 완료)

이슈 #10([프론트엔드] 상품 목록 API 연동)의 요구사항을 반영한 스펙.

## GET /api/products

### 요청

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
    "createdAt": "2026-09-20T09:00:00"
  }
]
```

상품이 없으면 빈 배열 `[]`.

### 응답 - 실패

| HTTP status | 설명 |
| --- | --- |
| 400 | status 파라미터 값이 `ProductStatus` enum 값도 아니고 `all`도 아님 |

### ⚠️ 프론트(C) 연동 시 주의 — 기존 임시 타입과 다른 부분

`frontend/src/data/products.ts`의 임시 `Product` 타입과 실제 응답이 두 가지 다르다:

1. **`id`가 `number`가 아니라 `string`(UUID)이다.** `Number(id)` 같은 변환 코드가 있다면 제거해야 함.
2. **`status` 값이 `"active" | "extended" | "closed"`가 아니라 실제 엔티티 enum 그대로다:**
   `PENDING`(등록대기) / `IN_PROGRESS`(진행중) / `EXTENDED`(마감연장) / `SOLD`(낙찰확정) / `UNSOLD`(유찰) / `COMPLETED`(완료).
   기본 목록 조회(status 파라미터 없음) 결과에는 `IN_PROGRESS`, `EXTENDED`만 나오므로, 목록 화면에서는 사실상 이 두 값만 처리하면 된다.
