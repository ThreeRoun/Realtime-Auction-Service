# 📜 Logging (Loki + Grafana Alloy + Grafana)

모든 서비스 컨테이너의 로그를 한곳에 모아 검색·분석하는 로그 스택입니다.

```text
auction-engine ┐
realtime-relay ├─ (컨테이너 로그) ─▶ Alloy ─▶ Loki ─▶ Grafana
frontend, db.. ┘                    수집      저장     검색·대시보드
```

수업에서 다룬 Loki + Promtail + Grafana 구성을 따르되, Promtail이 2026년 3월 2일 지원 종료되어
후속 수집기인 Grafana Alloy를 사용합니다.

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
