# 📈 Monitoring (Prometheus)

auction-engine의 상태(요청 수, 응답 속도, JVM 메모리 등)를 숫자로 계속 수집해서
추세를 볼 수 있게 하는 스택입니다. "서버가 켜져있냐"(헬스체크)가 아니라
"서버가 서서히 맛이 가고 있지는 않냐"를 보기 위한 용도입니다.

```text
auction-engine ─(15초마다 /actuator/prometheus 긁어감)─▶ Prometheus ─▶ Grafana
             (지표 노출)                              (수집·저장)      (조회·대시보드)
```

## 실행 방법

프로젝트 루트에서 기존 서비스 compose 파일과 함께 실행합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d
```

| 도구 | 주소 | 비고 |
| --- | --- | --- |
| Prometheus | <http://localhost:9090> | `Status → Targets`에서 auction-engine이 `UP`인지 확인 |

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

## 범위 (MVP)

- 지금은 **auction-engine만** 계측돼 있습니다. realtime-relay(Node.js)는 `ws` 서버가
  내부적으로 자체 HTTP 서버를 쓰고 있어서 `/metrics`를 붙이려면 서버 부트스트랩 구조를
  조금 바꿔야 합니다 - 다음 단계로 미뤘습니다.
- Grafana 대시보드(패널 구성)는 아직 없습니다. 위 Explore 쿼리로 우선 데이터가 들어오는지
  확인하고, 그 다음 대시보드 패널로 정리할 예정입니다.
