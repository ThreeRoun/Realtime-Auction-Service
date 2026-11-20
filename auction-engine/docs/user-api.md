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

사용자 정보 조회. **로그인이 필요하다** (`Authorization: Bearer <토큰>`, `auth-api.md` 참고).

- **본인 id**를 조회하면 이메일과 크레딧까지 전체 정보를 돌려준다.
- **다른 사용자의 id**를 조회하면 `id`와 `username`만 돌려준다.

사용자 id는 입찰 이력이나 상품 응답으로 누구나 알 수 있다. 이 API가 인증 없이 전체 정보를 내려주면 타인의 이메일과 크레딧이 그대로 노출되므로, 타인에게는 공개해도 되는 정보만 준다. 숨기는 필드는 값이 `null`인 것이 아니라 **필드 자체가 응답에 없다**.

### 응답 - 본인 조회 (200 OK)

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "username": "경매왕",
  "email": "user@example.com",
  "credit": 500000,
  "createdAt": "2026-09-20T09:00:00"
}
```

### 응답 - 타인 조회 (200 OK)

```json
{
  "id": "22222222-2222-2222-2222-222222222222",
  "username": "다른경매왕"
}
```

`passwordHash`는 어떤 경우에도 응답에 포함되지 않는다.

### 응답 - 실패

| HTTP status | 설명 |
| --- | --- |
| 401 | Authorization 헤더가 없거나 토큰이 유효하지 않음 (UNAUTHORIZED) |
| 404 | 존재하지 않는 사용자 (USER_NOT_FOUND) |
