# 사용자 API 명세 (구현 완료)

## POST /api/users

회원가입. 로그인/토큰 발급은 아직 없다 - 여기서 만든 id를 다른 API(상품 등록의 `sellerId`,
입찰의 `bidderId`)에 그대로 값으로 넘기는 현재 MVP 방식과 바로 연결된다 (통합 단계에서
실제 인증으로 교체 예정).

### 요청

```json
{
  "username": "경매왕",
  "email": "user@example.com",
  "password": "비밀번호",
  "initialCredit": 500000
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| username | string | 최대 50자, 중복 불가 |
| email | string | 이메일 형식, 중복 불가 |
| password | string | 4자 이상. BCrypt로 해시해서 저장한다 |
| initialCredit | integer | 선택, 0 이상. 결제 연동이 없는 MVP라 별도 "충전" API가 없어서 가입 시 바로 크레딧을 부여하는 임시방편. 생략 시 0 |

### 응답 - 성공 (201 Created)

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "username": "경매왕",
  "email": "user@example.com",
  "credit": 500000,
  "createdAt": "2026-09-20T09:00:00"
}
```

### 응답 - 실패

| HTTP status | code | 설명 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | username/email/password 형식이 잘못됨 |
| 409 | DUPLICATE_USER | 이미 있는 username 또는 email |

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
