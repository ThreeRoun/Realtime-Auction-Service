# Realtime-Auction-Service

## 🔨 실시간 경매 서비스

**팀 삼세판**의 DevOps 협업 프로젝트입니다.

물건 하나를 여러 사용자가 동시에 입찰하여 최고가로 낙찰받는 서비스를 만듭니다. 실제 결제는 연동하지 않고, 사용자마다 부여된 크레딧으로 낙찰을 처리합니다.

- **동시 입찰의 정확한 순서 판정** — 여러 입찰이 동시에 들어와도 하나만 최고가로 확정
- **마감 직전 입찰 처리(안티 스나이핑)** — 마감 직전 입찰 시 마감 시각 자동 연장

## 시스템 정의

| 항목 | 내용 |
| --- | --- |
| **시스템명** | 실시간 경매 서비스 |
| **정의** | 물건 하나를 여러 사용자가 동시에 입찰하여 최고가로 낙찰받는 서비스. 결제는 연동하지 않고 크레딧(가짜 화폐)으로 처리 |
| **핵심 기술 문제 1** | 동시 입찰의 정확한 순서 판정 — DB 트랜잭션/락으로 동시 요청 중 하나만 최고가로 확정 |
| **핵심 기술 문제 2** | 마감 직전 입찰 처리(안티 스나이핑) — 마감 N초 전 입찰 시 마감 시각 자동 연장 |

## 개발 범위

**MVP (필수)**

1. 상품 등록
2. 입찰 등록 및 최고가 판정
3. 동시 입찰 시 정확한 순서 처리
4. 마감 시각 도달 시 낙찰자 확정
5. 낙찰 결과 및 최고가의 실시간 반영

## 엔티티 설계

### 상품 (Product)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| id | UUID/PK | 상품 고유 ID |
| title | string | 상품명 |
| description | text | 상품 설명 |
| starting_price | integer | 시작가 |
| current_price | integer | 현재 최고가 |
| bid_unit | integer | 최소 입찰 단위 |
| seller_id | FK → User | 판매자 |
| status | enum | 등록대기 / 진행중 / 마감연장 / 낙찰확정 / 유찰 / 완료 |
| start_at | datetime | 경매 시작 시각 |
| end_at | datetime | 마감 시각 (연장 시 갱신) |
| winner_id | FK → User (nullable) | 낙찰자 |
| created_at | datetime | 생성 시각 |

### 입찰 (Bid)

방식: **로그 테이블** — 입찰마다 새 행을 추가한다. 동시 입찰 시 진 쪽 기록도 남겨 디버깅과 동시성 처리 증빙 자료로 쓴다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| id | UUID/PK | 입찰 고유 ID |
| product_id | FK → Product | 대상 상품 |
| bidder_id | FK → User | 입찰자 |
| amount | integer | 입찰 금액 |
| bid_at | datetime | 입찰 시각 (동시성 판정 근거) |
| is_valid | boolean | 최고가 갱신 성공 여부 |

### 사용자 (User)

크레딧 정책: **낙찰 확정 시 차감**. 입찰 시점엔 크레딧을 잠그지 않고, 마감 확정 순간 낙찰자의 크레딧만 차감한다. 크레딧 부족 시 낙찰 실패 처리 후 차순위 입찰자로 승계한다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| id | UUID/PK | 사용자 고유 ID |
| username | string | 닉네임 |
| email | string | 이메일 |
| password_hash | string | 비밀번호 해시 |
| credit | integer | 보유 크레딧 |
| created_at | datetime | 가입 시각 |

### WebSocket 이벤트

통신 방식: A(경매 엔진) → B(실시간 중계)는 **Redis Pub/Sub**. 두 서비스가 서로의 주소를 몰라도 되게 분리하고, 언어가 달라도(Java/Node) 동일한 방식으로 통신한다.

모든 이벤트는 이벤트별로 채널을 나누지 않고 **단일 채널 `auction_events`** 로 발행되며, B는 이 채널 하나만 구독해 모든 종류의 이벤트를 받는다. 그래서 payload 안에 `event` 필드로 이벤트 종류를 반드시 구분해야 하며, B는 이 메시지를 원문 그대로(pass-through) 브라우저에 전달하므로 아래 payload 형태가 곧 프론트가 받는 최종 포맷이다.

| 이벤트명 | 발생 시점 | payload |
| --- | --- | --- |
| `bid_placed` | 입찰 성공 시 | `{ event: "bid_placed", product_id, current_price, bidder_id, bid_at }` |
| `auction_extended` | 마감 연장 시 | `{ event: "auction_extended", product_id, new_end_at }` |
| `auction_closed` | 마감 확정 시 | `{ event: "auction_closed", product_id, winner_id, final_price }` |

---

## 기술 스택

| 파트 | 스택 |
| --- | --- |
| **경매 엔진 (A)** | Java/Kotlin + Spring Boot |
| **실시간 중계 (B)** | Node.js + ws + ioredis |
| **Frontend (C)** | React + TypeScript (Vite) |
| **DevOps (D)** | Docker, Docker Compose, GitHub Actions, Terraform, Kubernetes, Prometheus, Grafana |
