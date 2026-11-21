# 📜 Logging (Loki + Grafana Alloy + Grafana)

모든 서비스 컨테이너의 로그를 한곳에 모아 검색·분석하는 로그 스택입니다.

```text
auction-engine ┐
realtime-relay ├─ (컨테이너 로그) ─▶ Alloy ─▶ Loki ─▶ Grafana
frontend, db.. ┘                    수집      저장     검색·대시보드
```

수업에서 다룬 Loki + Promtail + Grafana 구성을 따르되, Promtail이 2026년 3월 2일 지원 종료되어
후속 수집기인 Grafana Alloy를 사용합니다.

> 이 문서는 **로컬 docker compose** 기준입니다. 학교 서버(k3s) 클러스터에서는 같은 스택이 `k8s/monitoring`으로
> 배포되며, Grafana는 `http://<서버>/grafana`로 접속합니다 - [k8s/README.md](../k8s/README.md#모니터링로깅-k8smonitoring) 참고.

## 실행 방법

프로젝트 루트에서 기존 서비스 compose 파일과 함께 실행합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.logging.yml up -d --build
```

| 도구 | 주소 | 비고 |
| --- | --- | --- |
| Grafana | <http://localhost:3001> | 기본 계정 `admin` / `admin` |
| Loki | <http://localhost:3100> | Grafana에 데이터 소스로 자동 등록됨 |
| Alloy | <http://localhost:12345> | 수집 상태(파이프라인) 확인용 UI |

종료할 때도 두 파일을 함께 지정합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.logging.yml down
```

## 로그 검색하기

Grafana에서 왼쪽 메뉴의 **Explore** → 데이터 소스 **Loki**를 선택하고 LogQL을 입력합니다.

| 목적 | LogQL |
| --- | --- |
| 중계 엔진 로그 전체 | `{service="realtime-relay"}` |
| 경매 엔진의 에러만 | `{service="auction-engine"} \|= "ERROR"` |
| 모든 서비스에서 특정 상품 ID 검색 | `{project=~".+"} \|= "<상품 ID>"` |
| 서비스별 분당 로그 수 | `sum by (service) (count_over_time({project=~".+"}[1m]))` |

로그가 JSON 형식이면 `| json`으로 필드를 꺼내 집계할 수 있습니다.

```logql
sum(sum_over_time({service="realtime-relay"} | json | unwrap sent [5m]))
```

## 라벨

Alloy가 각 로그에 붙이는 라벨입니다.

| 라벨 | 값 |
| --- | --- |
| `service` | compose 서비스 이름 (`auction-engine`, `realtime-relay`, `frontend`, `db`, `redis` 등) |
| `container` | 컨테이너 이름 |
| `project` | compose 프로젝트 이름 |

## AI 로그 분석

여러 서비스의 로그를 시간순으로 엮어 Claude API로 장애 원인을 분석하는 스크립트는
[ai-log-analyzer/README.md](ai-log-analyzer/README.md)를 참고하세요.

## 트레이스 (Tempo)

앱이 OpenTelemetry(OTLP)로 보낸 트레이스를 Alloy가 받아 Tempo에 저장하고, Grafana에서 조회합니다.

```text
앱(엔진, relay) ──OTLP──▶ Alloy(4317 gRPC / 4318 HTTP) ──▶ Tempo ◀── Grafana
```

- Tempo 버전: 2.10.x (3.x는 단일 실행에도 Kafka가 필요해서 2.10 사용, 2027-04까지 유지보수)
- 보관 기간: 7일 (Loki와 같게)
- 앱은 Alloy로 보냅니다. compose 안에서는 `alloy:4317`/`alloy:4318`, Mac에서 직접 실행할 때는 `localhost:4317`/`localhost:4318`

### 수집 경로 확인 (테스트 트레이스)

```bash
python3 logging/tempo/send_test_trace.py
```

출력된 `trace_id`를 Grafana(`http://localhost:3001`) → Explore → Tempo → TraceQL에 붙여 넣으면
부모 span(`POST /api/bids (테스트)`) 안에 자식 span(`redis publish (테스트)`)이 보입니다.

### 트레이스 ↔ 로그 연결

- 트레이스 → 로그: span 옆 LOG 아이콘을 누르면 같은 `trace_id`가 들어 있는 Loki 로그를 찾습니다.
- 로그 → 트레이스: 로그 줄에 `"trace_id":"..."`가 있으면 Grafana가 링크로 만들어 Tempo에서 엽니다.
- 로그에 `trace_id`가 찍히려면 엔진, relay에 OpenTelemetry 계측이 필요합니다 (트레이스 도입 이슈 참고).

### 주의: Loki 데이터 소스 uid 변경

Tempo 연결을 위해 로컬 Loki 데이터 소스의 uid를 k8s와 같은 `loki`로 고정했습니다.
기존 로컬 Grafana에는 uid 없이 등록돼 있어서, `loki.yml`의 `deleteDatasources`로
시작할 때마다 지우고 다시 등록합니다. 로그 데이터에는 영향이 없습니다.
