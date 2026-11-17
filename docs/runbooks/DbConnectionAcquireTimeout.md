---
alert: DbConnectionAcquireTimeout
severity: critical
service: auction-engine
---

# DbConnectionAcquireTimeout

## 의미와 영향

경매 엔진이 DB 커넥션 풀(HikariCP)에서 **30초 안에 커넥션을 얻지 못해 요청이 실패**했다 (최근 5분 사이 1건 이상).
한 건이라도 발생하면 바로 뜬다.

- 풀 크기는 기본값 10개(엔진 replica 1개 기준)다. 10개가 모두 사용 중이면 나머지 요청은 줄을 서고, 30초가 지나면 실패한다.
- 원인은 크게 셋이다.
  1. **DB가 느리거나 응답 없음**: DB 다운/재시작, 디스크 가득 참
  2. **커넥션을 오래 붙잡는 트랜잭션**: 입찰의 상품 row 락 대기, 느린 쿼리
  3. **요청 급증**: 마감 직전 입찰 폭주

## 확인

### C1. 풀 상태 추이 `[도구: query_prometheus]`

```promql
max by (pool) (hikaricp_connections_active{application="auction-engine"})
max by (pool) (hikaricp_connections_max{application="auction-engine"})
max by (pool) (hikaricp_connections_pending{application="auction-engine"})
increase(hikaricp_connections_timeout_total{application="auction-engine"}[5m])
max by (pool) (rate(hikaricp_connections_usage_seconds_sum{application="auction-engine"}[5m]) / rate(hikaricp_connections_usage_seconds_count{application="auction-engine"}[5m]))
```

마지막 식은 커넥션 1회 사용 시간 평균이다. 평소(수 ms~수십 ms)보다 크게 늘었다면 트랜잭션이 오래 걸리는 것이다(원인 1, 2).

### C2. DB Pod 상태 `[도구: k8s_status]`

`auction-db-0` Pod의 Ready, 재시작 횟수, 마지막 종료 사유. Ready가 아니거나 방금 재시작했다면 → 조치 A.

### C3. 요청량과 입찰 몰림 `[도구: query_prometheus]`

```promql
sum by (method, uri) (rate(http_server_requests_seconds_count{application="auction-engine", uri!~"/actuator.*"}[1m]))
sum(rate(auction_bid_placed_total[5m]))
```

평소보다 입찰 요청이 몇 배로 늘었다면 원인 3, 또는 원인 2(입찰 락 경합)다.

### C4. 엔진/DB 로그 `[도구: query_loki]`

```logql
{namespace="auction", service="auction-engine"} | json | level="ERROR"
{namespace="auction", service="auction-db"} |~ "FATAL|PANIC|ERROR|No space left"
```

DB 로그의 `No space left on device`는 디스크, `too many clients`는 DB 연결 수 한도, `deadlock detected`는 교착 상태다.

### C5. 서버 디스크 `[도구: query_prometheus]`

```promql
node_filesystem_avail_bytes{job="node-exporter", mountpoint="/"} / node_filesystem_size_bytes{job="node-exporter", mountpoint="/"}
```

5% 미만이면 DB 쓰기가 막혀 트랜잭션이 끝나지 않는다 → [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md).

### C6. DB의 오래된 트랜잭션과 락 대기 `[사람]`

```bash
kubectl -n auction exec -i auction-db-0 -- sh -c 'psql -U "$POSTGRES_USER" -d auction' <<'SQL'
SELECT pid, state, now() - xact_start AS xact_age, wait_event_type, wait_event, left(query, 80) AS query
FROM pg_stat_activity
WHERE datname = 'auction' AND state <> 'idle'
ORDER BY xact_start;
SQL
```

- `state = idle in transaction`이 오래(수십 초 이상) 열려 있음 → 커밋/롤백 없이 커넥션을 붙잡은 트랜잭션 (조치 B)
- `wait_event_type = Lock` 행이 많음 → 맨 위의 오래된 트랜잭션이 락을 잡고 있음 (조치 B)

## 조치

### A. DB 다운/재시작 → DB Runbook으로

- **조건**: C2에서 auction-db가 Ready 아님 또는 방금 재시작, C4에 DB 연결 실패
- **실행**: [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md)을 따른다. DB가 돌아오면 커넥션 풀은 자동으로 다시 채워진다.

### B. 커넥션을 붙잡은 트랜잭션 → 해당 트랜잭션 종료

- **조건**: C6에서 `idle in transaction`이 오래 열려 있거나, 다른 요청들을 막고 있는 오래된 트랜잭션이 있음
- **실행**:

  ```bash
  # C6 결과의 pid를 넣는다. 먼저 cancel(쿼리만 취소)을 시도하고, 안 되면 terminate(연결 종료)
  kubectl -n auction exec -i auction-db-0 -- sh -c 'psql -U "$POSTGRES_USER" -d auction' <<'SQL'
  SELECT pg_cancel_backend(<pid>);
  SQL
  # 10초 뒤에도 남아 있으면
  kubectl -n auction exec -i auction-db-0 -- sh -c 'psql -U "$POSTGRES_USER" -d auction' <<'SQL'
  SELECT pg_terminate_backend(<pid>);
  SQL
  ```

- **주의**
  - 종료된 트랜잭션은 롤백된다. 입찰 트랜잭션이었다면 그 입찰은 저장되지 않는다(사용자는 5xx를 받음).
  - **한 번에 하나씩**, 가장 오래된 것부터 정리한다. 여러 개를 한꺼번에 끊지 않는다.
  - 같은 쿼리가 계속 다시 쌓이면 코드 문제다 → 조치 D.

### C. 입찰 폭주(마감 직전 등) → 상황 공유하며 지켜보기

- **조건**: C3에서 요청 급증, DB와 디스크는 정상, 오래 걸리는 트랜잭션은 없음(모두 짧은 락 대기)
- **실행**: 엔진은 replica 1개로 고정이라 스케일 아웃으로 해결할 수 없다. 몰린 상품이 마감되면 대개 몇 분 안에 해소된다.
  어느 상품인지(로그/DB의 product_id) 공유하고 지켜본다. 반복되면 풀 크기 조정이나 입찰 처리 구조 개선 이슈를 만든다.

### D. 최근 배포 이후 발생 → 롤백

- **조건**: `auction-engine`에 알람 직전 새 배포가 있었고 `[도구: rollout_history]`, 그 이후 커넥션 사용 시간이 늘어남(C1)
- **실행**:

  ```bash
  kubectl -n auction rollout undo deploy/auction-engine
  kubectl -n auction rollout status deploy/auction-engine --timeout=300s
  ```

- **주의**: 원인 PR을 revert하는 PR을 올린다. DB 마이그레이션이 포함된 배포였다면 [AuctionEngineDown](AuctionEngineDown.md) 조치 A의 주의 사항을 먼저 본다.

### E. 원인 불명으로 계속 실패 → 엔진 재시작 (최후 수단)

- **조건**: 위 조건에 해당하지 않고, 5분 이상 계속 타임아웃이 늘어남
- **실행**: 스레드 덤프를 남긴 뒤 재시작한다. [AuctionEngineDown](AuctionEngineDown.md) 조치 B와 같다.
- **주의**: 재시작하는 동안(수십 초) 모든 API가 실패한다.

## 해소 확인

```promql
increase(hikaricp_connections_timeout_total{application="auction-engine"}[5m])
```

0이 되면 5분 뒤 RESOLVED가 온다. pending이 0으로 유지되는지 Grafana `auction-engine` 대시보드("커넥션 사용 현황")에서 본다.

## 관련

- [BidEndpoint5xxRate](BidEndpoint5xxRate.md), [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md), [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md)
- 같이 뜰 수 있는 알람: `DbConnectionPoolSaturated` (warning, 이 알람이 떠 있으면 숨겨짐)
