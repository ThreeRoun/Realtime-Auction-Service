# 사용자 API 명세 (구현 완료)

## GET /api/users/{id}

사용자 프로필 조회. 로그인/인증 체계가 아직 없어서, bidderId/sellerId처럼 ID로 직접 조회한다.

### 응답 (200 OK)

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "username": "경매왕",
  "email": "user@example.com",
  "credit": 500000,
  "createdAt": "2026-09-20T09:00:00"
}
```

`passwordHash`는 응답에 포함되지 않는다.

### 응답 - 실패

| HTTP status | 설명 |
| --- | --- |
| 404 | 존재하지 않는 사용자 (USER_NOT_FOUND) |
