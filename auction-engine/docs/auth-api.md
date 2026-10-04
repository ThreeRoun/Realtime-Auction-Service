# 로그인 API 명세 (구현 완료)

## POST /api/login

로그인. 성공하면 JWT를 발급한다. 아직은 이 토큰을 검증해서 적용하는 API가 없고(이슈 #36),
토큰 발급까지만 구현된 상태다.

### 요청

```json
{
  "username": "경매왕",
  "password": "비밀번호"
}
```

### 응답 - 성공 (200 OK)

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresAt": "2026-10-05T16:00:00"
}
```

토큰의 subject(`sub`)에 user id(UUID)가 들어있다. 서명 검증 키는 `jwt.secret` 설정값
(환경변수 `JWT_SECRET`으로 덮어쓸 수 있음), 만료 시간은 `jwt.expiration-minutes`
(기본 60분, 환경변수 `JWT_EXPIRATION_MINUTES`)로 조정 가능하다.

### 응답 - 실패

| HTTP status | code | 설명 |
| --- | --- | --- |
| 401 | INVALID_CREDENTIALS | username이 없거나 비밀번호가 틀림 (어느 쪽이 틀렸는지는 구분해서 알려주지 않음 - username 존재 여부가 외부에 노출되지 않도록) |
| 400 | INVALID_REQUEST | username/password 누락 |

## 참고

- 회원가입은 `user-api.md`의 `POST /api/users` 참고.
- 기존 상품등록(`sellerId`)/입찰(`bidderId`) API를 이 토큰 기반 인증으로 전환하는 작업은
  이슈 #36에서 별도로 진행한다.
