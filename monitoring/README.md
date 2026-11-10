# 📈 Monitoring (Prometheus + Alertmanager)

auction-engine의 상태(요청 수, 응답 속도, JVM 메모리 등)를 숫자로 계속 수집해서
추세를 볼 수 있게 하는 스택입니다. "서버가 켜져있냐"(헬스체크)가 아니라
"서버가 서서히 맛이 가고 있지는 않냐"를 보기 위한 용도입니다.

```text
auction-engine ─(15초마다 /actuator/prometheus 긁어감)─▶ Prometheus ─▶ Grafana
             (지표 노출)                              (수집·저장)      (조회·대시보드)
                                                          │ 알람 규칙 평가
                                                          ▼
                                                     Alertmanager ─▶ Discord 채널
                                                   (그룹핑·중복 억제)
```

> 이 문서는 **로컬 docker compose** 기준입니다. 학교 서버(k3s) 클러스터에서는 같은 스택이 `k8s/monitoring`으로
> 배포되며, Grafana는 `http://<서버>/grafana`로 접속합니다 - [k8s/README.md](../k8s/README.md#모니터링로깅-k8smonitoring) 참고.

## 실행 방법

프로젝트 루트에서 기존 서비스 compose 파일과 함께 실행합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d
```

| 도구 | 주소 | 비고 |
| --- | --- | --- |
| Prometheus | <http://localhost:9090> | `Status → Targets`에서 auction-engine이 `UP`인지 확인, `Alerts`에서 규칙 상태 확인 |
| Alertmanager | <http://localhost:9093> | 현재 발생(firing) 중인 알람, silence 관리 |

로깅 스택(`docker-compose.logging.yml`, PR #95)과 같이 띄우면, 그쪽 Grafana(<http://localhost:3001>)에서
지표(Prometheus)와 로그(Loki)를 한 화면에서 볼 수 있습니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.logging.yml -f docker-compose.monitoring.yml up -d --build
```

종료할 때도 같은 조합으로 `down`을 실행합니다.

## 지표 확인하기

Grafana에서 왼쪽 메뉴의 **Explore** → 데이터 소스 **Prometheus**를 선택하고 PromQL을 입력합니다.

| 목적 | PromQL |
| --- | --- |
| HTTP 요청 수 (초당, 엔드포인트별) | `sum by (uri) (rate(http_server_requests_seconds_count{application="auction-engine"}[1m]))` |
| HTTP 평균 응답 시간 | `rate(http_server_requests_seconds_sum[1m]) / rate(http_server_requests_seconds_count[1m])` |
| JVM 힙 메모리 사용량 | `jvm_memory_used_bytes{application="auction-engine", area="heap"}` |
| DB 커넥션 풀 사용 중인 커넥션 수 | `hikaricp_connections_active{application="auction-engine"}` |

## 알람 (Alerting)

알람 규칙과 알림 라우팅은 **k8s 환경과 같은 파일**을 씁니다(compose에서 마운트).

- 알람 규칙: [`k8s/monitoring/config/rules/`](../k8s/monitoring/config/rules/) - 이 표는 `auction-engine.rules.yml`
- 알림 라우팅: [`k8s/monitoring/config/alertmanager.yml`](../k8s/monitoring/config/alertmanager.yml)

서버/Kubernetes 상태 알람(`node.rules.yml`, `kubernetes.rules.yml`)은 node-exporter, kube-state-metrics가 있는
k8s 환경에서만 동작합니다 - [k8s/README.md](../k8s/README.md#알람) 참고.

| 알람 | 심각도 | 조건 |
| --- | --- | --- |
| `AuctionEngineDown` | critical | 응답하는 엔진 인스턴스가 없음 1분 지속 (k8s에서는 Pod가 아예 없는 경우 포함) |
| `AuctionEngineHigh5xxRate` | critical | 전체 API 5xx 비율 > 5% (2분, 최소 0.2 req/s) |
| `BidEndpoint5xxRate` | critical | `POST /api/bids` 5xx 비율 > 2% (1분, 최소 0.1 req/s) - 4xx(정상 거절)는 제외 |
| `DbConnectionAcquireTimeout` | critical | 최근 5분 내 HikariCP 커넥션 획득 타임아웃 발생 |
| `BidEndpointLatencyHigh` | warning | 입찰 API p99 > 500ms (3분) |
| `ApiLatencyHigh` | warning | 엔드포인트별 p99 > 1s (5분) |
| `DbConnectionPoolSaturated` | warning | 풀 사용률 ≥ 90% + 대기 스레드 존재 (2분) |
| `JvmOldGenAfterGcHigh` | warning | GC 후 Old Gen 사용률 > 90% (5분) - 메모리 누수 신호 |
| `JvmGcPauseHigh` | warning | 실행 시간의 10% 이상 GC 정지 (5분) |
| `AuctionEngineRestarted` | warning | 최근 15분 내 프로세스 재시작 |

- p99 지연 알람을 위해 auction-engine이 HTTP 응답 시간 히스토그램(`http_server_requests_seconds_bucket`)을
  내보내도록 설정했습니다(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`).
- `AuctionEngineDown`이 떠 있으면 나머지 엔진 알람은 억제(inhibit)되고, 같은 서비스에 critical이 떠 있으면
  warning은 억제됩니다 - 근본 원인 알람 하나에 집중하기 위해서입니다.
- 알람은 **Discord 채널**로 발송되고(해소 시 `RESOLVED` 메시지도 발송), Alertmanager UI나 Grafana의
  `Alerting` 메뉴(Alertmanager 데이터 소스)에서도 확인할 수 있습니다.

### Discord 알림 연결

webhook URL은 비밀값이라 git에 올리지 않고, 각자 서버/로컬에서 파일로 만들어 둡니다
(`monitoring/alertmanager/secrets/`는 `.gitignore` 처리돼 있습니다).

1. Discord 채널 설정 → `연동(Integrations)` → `웹후크(Webhooks)` → `새 웹후크` → `웹후크 URL 복사`
2. 프로젝트 루트에서 URL을 파일로 저장합니다(끝에 줄바꿈 없이).

   ```bash
   mkdir -p monitoring/alertmanager/secrets
   printf '%s' 'https://discord.com/api/webhooks/...' > monitoring/alertmanager/secrets/discord-webhook-url
   ```

3. 모니터링 스택을 띄우거나(이미 떠 있으면) Alertmanager를 재시작합니다.

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d
   docker compose -f docker-compose.yml -f docker-compose.monitoring.yml restart alertmanager
   ```

4. 테스트 알람을 직접 넣어 Discord에 메시지가 오는지 확인합니다(약 10초 후 도착).

   ```bash
   curl -XPOST -H 'Content-Type: application/json' http://localhost:9093/api/v2/alerts \
     -d '[{"labels":{"alertname":"DiscordTest","severity":"critical","service":"test"},"annotations":{"summary":"Discord 연결 테스트","description":"무시해도 되는 테스트 알람입니다."}}]'
   ```

- 메시지 예: 제목 `[FIRING:1] BidEndpoint5xxRate (critical)`, 본문에 요약/설명/시작 시각(KST).
- 해소(`RESOLVED`) 메시지는 즉시가 아니라 다음 그룹 주기(`group_interval`, 최대 5분) 안에 옵니다.
- 파일이 없으면 Alertmanager는 정상 기동하지만 발송 시점에 실패 로그(`notify retry canceled`)를 남깁니다.

### 규칙 검증

규칙을 수정했다면 PR 전에 로컬에서 문법 검사와 단위 테스트를 돌립니다(CI `Lint / monitoring` 잡에서도 실행).

```bash
# 알람 규칙 문법 + 단위 테스트 (가짜 시계열로 "이때 알람이 떠야/뜨지 말아야 한다" 검증)
docker run --rm -v "$PWD/k8s/monitoring/config/rules":/r -w /r --entrypoint sh prom/prometheus:v3.0.1 \
  -c 'promtool check rules *.rules.yml && cd tests && promtool test rules *.test.yml'
# Alertmanager 설정
docker run --rm -v "$PWD/k8s/monitoring/config":/a --entrypoint amtool \
  prom/alertmanager:v0.28.1 check-config /a/alertmanager.yml
```

## 범위 (MVP)

- 지금은 **auction-engine만** 계측돼 있습니다. realtime-relay(Node.js)는 `ws` 서버가
  내부적으로 자체 HTTP 서버를 쓰고 있어서 `/metrics`를 붙이려면 서버 부트스트랩 구조를
  조금 바꿔야 합니다 - 다음 단계로 미뤘습니다.
- Grafana 대시보드(패널 구성)는 아직 없습니다. 위 Explore 쿼리로 우선 데이터가 들어오는지
  확인하고, 그 다음 대시보드 패널로 정리할 예정입니다.
- 서버(k3s)에는 같은 스택이 `k8s/monitoring`으로 배포됩니다. 알람 규칙과 Alertmanager 설정은 같은 파일을 쓰고,
  지표 수집 설정(`prometheus/prometheus.yml`)만 환경별로 따로 있습니다.
- Postgres/Redis 자체 지표(exporter)는 수집하지 않습니다. DB 문제는 엔진 쪽 HikariCP 지표로 간접 감지합니다.
