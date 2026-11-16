---
alert: BidEndpoint5xxRate
severity: critical
service: auction-engine
---

# BidEndpoint5xxRate

## 의미와 영향

입찰 API(`POST /api/bids`) 요청 중 **5xx 응답이 1분 이상 2%를 넘었다** (초당 0.1건 이상 입찰이 있을 때만).
입찰 실패는 곧바로 사용자 손실이라 전체 API보다 기준이 낮다.

- 쿨다운(429), 낮은 입찰가, 자기 상품 입찰 같은 거절은 4xx라 포함되지 않는다.
- 입찰은 **상품 row에 비관적 락(`SELECT ... FOR UPDATE`)을 건 트랜잭션**으로 처리된다. 인기 상품에 입찰이 몰리면
  락을 기다리는 요청들이 DB 커넥션(기본 풀 10개)을 붙잡고 있어 커넥션 풀이 고갈될 수 있다. 이 알람의 가장 흔한 원인 후보다.

## 확인

### C1. 커넥션 풀 고갈 여부 `[도구: query_prometheus]`

```promql
max by (pool) (hikaricp_connections_active{application="auction-engine"})
max by (pool) (hikaricp_connections_max{application="auction-engine"})
max by (pool) (hikaricp_connections_pending{application="auction-engine"})
increase(hikaricp_connections_timeout_total{application="auction-engine"}[5m])
```

active가 max에 붙어 있고 pending이 0보다 크거나 timeout이 늘었다면 → 조치 B.

### C2. 입찰 몰림 여부 `[도구: query_prometheus]`

```promql
sum(rate(http_server_requests_seconds_count{application="auction-engine", method="POST", uri="/api/bids"}[1m]))
sum(rate(auction_bid_placed_total[5m]))
sum by (reason) (rate(auction_bid_rejected_total[5m]))
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="auction-engine", method="POST", uri="/api/bids"}[5m])))
```

평소보다 입찰 요청이 급증하고 p99 응답 시간이 수 초 이상이면 락 경합 가능성이 높다 (마감 직전 인기 상품 등).

### C3. 최근 배포 `[도구: rollout_history]`

알람 시작 30분 전 이내에 `auction-engine`에 새 ReplicaSet이 생겼는지. 배포 직후부터라면 → 조치 A.

### C4. 입찰 에러 로그 `[도구: query_loki]`

```logql
{namespace="auction", service="auction-engine"} | json | level="ERROR"
```

| 로그에 보이는 것 | 의미 | 다음 |
| --- | --- | --- |
| `Connection is not available, request timed out after 30000ms` | 커넥션 풀 고갈 | 조치 B |
| `PSQLException`, `deadlock detected`, `canceling statement` | DB 쪽 락/쿼리 문제 | 조치 B |
| `RedisConnectionFailureException` | 입찰 저장 후 이벤트 발행 실패 | [AuctionEngineHigh5xxRate](AuctionEngineHigh5xxRate.md) 조치 C |
| `No space left on device`, `could not extend file` | 디스크 가득 참 | [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md) |
| 코드 예외(`NullPointerException` 등) | 버그 | C3 확인 후 조치 A 또는 D |

### C5. DB에서 오래 걸리는 트랜잭션과 락 대기 `[사람]`

```bash
kubectl -n auction exec -i auction-db-0 -- sh -c 'psql -U "$POSTGRES_USER" -d auction' <<'SQL'
SELECT pid, state, now() - xact_start AS xact_age, wait_event_type, wait_event, left(query, 80) AS query
FROM pg_stat_activity
WHERE datname = 'auction' AND state <> 'idle'
ORDER BY xact_start;
SQL
```

`wait_event_type = Lock`인 행이 많고, 맨 위(가장 오래된 트랜잭션)가 수십 초 이상 열려 있으면 그 트랜잭션이 락을 붙잡고 있는 것이다.

## 조치

### A. 직전 배포로 인한 장애 → 이전 버전으로 롤백

- **조건**: C3에서 배포 직후부터 발생, C4 로그가 새 코드 문제를 가리킴
- **실행**:

  ```bash
  kubectl -n auction rollout undo deploy/auction-engine
  kubectl -n auction rollout status deploy/auction-engine --timeout=300s
  ```

- **주의**: 원인 PR을 revert하는 PR을 바로 올린다. DB 마이그레이션이 포함된 배포였다면 [AuctionEngineDown](AuctionEngineDown.md) 조치 A의 주의 사항을 먼저 본다.

### B. 커넥션 풀 고갈 / 락 경합 → DB Runbook으로

- **조건**: C1에서 풀 고갈, 또는 C4/C5에서 락 대기
- **실행**: [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md)의 조치를 따른다 (오래된 트랜잭션 정리 등).
- **주의**: 엔진은 replica 1개로 고정이라([k8s/README.md](../../k8s/README.md) "현재 제약") 스케일 아웃으로 해결할 수 없다.
  입찰 몰림이 원인이고 몇 분 안에 마감되는 상품이라면, 마감 후 자연 해소되는 경우가 많다. 해소 여부를 지켜보며 공유한다.

### C. Redis/디스크가 원인 → 해당 Runbook으로

- **조건**: C4에서 Redis 또는 디스크 에러
- **실행**: [AuctionEngineHigh5xxRate](AuctionEngineHigh5xxRate.md) 조치 C(Redis), [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md)

### D. 배포와 무관한 버그 → 담당자 호출

- **조건**: C1~C2 정상(풀 여유, 입찰량 평소 수준), C3에서 최근 배포 없음, C4에 코드 예외
- **실행**: 예외와 상품 ID(로그에 있다면)를 Discord 스레드에 공유하고 엔진 담당자를 호출한다.

## 해소 확인

Grafana `auction-engine` 대시보드의 "엔드포인트별 5xx"에서 `POST /api/bids`가 사라지고, "입찰 응답 시간" p99가 0.5초 아래로 돌아오는지 본다.

## 관련

- [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md), [AuctionEngineHigh5xxRate](AuctionEngineHigh5xxRate.md)
- 같이 뜰 수 있는 알람: `BidEndpointLatencyHigh`, `DbConnectionPoolSaturated` (warning, 이 알람이 떠 있으면 숨겨짐)
