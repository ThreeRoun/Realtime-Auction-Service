---
alert: AuctionEngineHigh5xxRate
severity: critical
service: auction-engine
---

# AuctionEngineHigh5xxRate

## 의미와 영향

경매 엔진의 전체 API(`/actuator` 제외) 요청 중 **5xx 응답이 2분 이상 5%를 넘었다** (초당 0.2건 이상 요청이 있을 때만).
엔진은 떠 있지만 요청 일부가 서버 오류로 실패하고 있다.

- 4xx(잘못된 입찰가, 쿨다운 429 등)는 정상적인 거절이라 포함되지 않는다.
- 입찰 API만 문제라면 [BidEndpoint5xxRate](BidEndpoint5xxRate.md)가 더 빨리(기준 2%) 뜬다.
- DB가 완전히 끊기면 엔진이 readiness 실패로 Service에서 빠져 요청 자체를 받지 못한다.
  그때는 이 알람 대신 사용자에게 502/503(nginx)이 보이고, `KubeDeploymentReplicasUnavailable`이나 DB 알람이 뜬다.

## 확인

### C1. 어떤 엔드포인트가 실패하는가 `[도구: query_prometheus]`

```promql
sum by (method, uri, status) (rate(http_server_requests_seconds_count{application="auction-engine", status=~"5..", uri!~"/actuator.*"}[5m]))
```

- 특정 엔드포인트 하나에 몰림 → 그 기능의 코드/데이터 문제 (C3, C4)
- 거의 모든 엔드포인트 → 공통 의존성(DB, 디스크) 문제 (C5, C6)

### C2. 언제부터인가 + 최근 배포 `[도구: query_prometheus]` `[도구: rollout_history]`

5xx 비율이 오르기 시작한 시각과, `auction-engine` Deployment에 새 ReplicaSet(새 이미지 SHA)이 생긴 시각을 비교한다.

```promql
sum(rate(http_server_requests_seconds_count{application="auction-engine", status=~"5..", uri!~"/actuator.*"}[1m]))
/ sum(rate(http_server_requests_seconds_count{application="auction-engine", uri!~"/actuator.*"}[1m]))
```

배포 직후(수 분 이내)부터 시작했다면 → 조치 A.

### C3. 에러 로그 `[도구: query_loki]`

```logql
{namespace="auction", service="auction-engine"} | json | level="ERROR"
```

가장 많이 나오는 예외(`stack_trace` 첫 줄)와 `logger_name`을 본다. 자주 보이는 패턴:

| 로그에 보이는 것 | 의미 | 다음 |
| --- | --- | --- |
| `SQLTransientConnectionException`, `Connection is not available, request timed out` | DB 커넥션 풀 고갈 | [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md) |
| `PSQLException`, `Connection refused` | DB 다운/재시작 중 | C5 |
| `could not extend file`, `No space left on device` | 디스크 가득 참 | C6 |
| `RedisConnectionFailureException` | Redis 장애 (입찰 이벤트 발행 실패) | 조치 C |
| `NullPointerException`, `IllegalStateException` 등 코드 예외 | 버그 | C2 확인 후 조치 A 또는 D |

### C4. 엔진 Pod 상태 `[도구: k8s_status]`

`app=auction-engine` Pod의 재시작 횟수와 마지막 종료 사유. 재시작이 반복되면 재시작 직후 요청 실패가 5xx로 잡힌다 → [AuctionEngineDown](AuctionEngineDown.md)의 확인 절차를 따른다.

### C5. DB 상태 `[도구: k8s_status]` `[도구: query_prometheus]`

`auction-db-0` Pod가 Ready인지, 엔진의 커넥션 풀 상태.

```promql
max by (pool) (hikaricp_connections_active{application="auction-engine"})
max by (pool) (hikaricp_connections_pending{application="auction-engine"})
increase(hikaricp_connections_timeout_total{application="auction-engine"}[5m])
```

### C6. 서버 디스크 `[도구: query_prometheus]`

```promql
node_filesystem_avail_bytes{job="node-exporter", mountpoint="/"} / node_filesystem_size_bytes{job="node-exporter", mountpoint="/"}
```

5% 미만이면 → [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md).

## 조치

### A. 직전 배포로 인한 장애 → 이전 버전으로 롤백

- **조건**: C2에서 배포 직후부터 5xx 증가, C3 로그가 새 코드 문제를 가리킴
- **실행**:

  ```bash
  kubectl -n auction rollout undo deploy/auction-engine
  kubectl -n auction rollout status deploy/auction-engine --timeout=300s
  ```

- **주의**: 원인 PR을 revert하는 PR을 바로 올린다 (다음 머지 때 다시 배포되므로).
  DB 마이그레이션이 포함된 배포였다면 [AuctionEngineDown](AuctionEngineDown.md) 조치 A의 주의 사항을 먼저 본다.

### B. DB/디스크가 원인 → 해당 Runbook으로

- **조건**: C3/C5/C6에서 DB 커넥션, DB 다운, 디스크 문제가 확인됨
- **실행**: [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md), [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md), [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md) 중 해당 문서를 따른다.

### C. Redis 장애 → Redis 재시작

- **조건**: C3에 `RedisConnectionFailureException`, `auction-redis` Pod가 Ready 아님 또는 재시작 반복
- **실행**:

  ```bash
  kubectl -n auction get pods -l app=auction-redis
  kubectl -n auction rollout restart deploy/auction-redis
  ```

- **주의**: 입찰 이벤트는 DB 커밋 후에 Redis로 발행된다. 이 구간에서 실패한 요청은 **입찰은 저장됐는데 응답이 5xx**였을 수 있다.
  사용자 문의가 오면 DB의 입찰 기록을 기준으로 안내한다. Redis는 캐시/중계용이라 재시작해도 영구 데이터는 잃지 않는다.

### D. 특정 기능 버그(배포와 무관) → 담당자 호출

- **조건**: C1에서 특정 엔드포인트에만 5xx, C2에서 최근 배포 없음 (특정 데이터에서만 터지는 버그 등)
- **실행**: 자동으로 할 수 있는 조치가 없다. 로그의 예외와 요청 경로를 Discord 스레드에 공유하고 엔진 담당자를 호출한다.
  입찰처럼 핵심 기능이 아니면, 수정 배포 전까지 영향 범위만 공유하고 기다린다.

## 해소 확인

C2의 1분 단위 5xx 비율이 1% 아래로 내려가는지 Grafana `auction-engine` 대시보드("5xx 비율", "엔드포인트별 5xx")에서 본다.
5분 평균 기준이라 RESOLVED는 정상화 후 몇 분 뒤에 온다.

## 관련

- [BidEndpoint5xxRate](BidEndpoint5xxRate.md), [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md)
- 같이 뜰 수 있는 알람: `ApiLatencyHigh`, `DbConnectionPoolSaturated` (warning, 이 알람이 떠 있으면 숨겨짐)
