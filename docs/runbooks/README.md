# Runbook (장애 대응 매뉴얼)

알람이 왔을 때 **무엇을 확인하고 어떤 조치를 할지** 미리 정해 둔 문서입니다.
알람 이름과 같은 파일명(`<알람명>.md`)으로 두고, Discord 알림에 링크(`runbook_url`)가 붙습니다.

- 장애 중에는 이 문서의 **확인 → 조치** 순서를 따릅니다. 문서에 없는 조치는 담당자와 상의한 뒤 실행합니다.
- 트리아지 봇(예정)도 이 문서를 읽고, 확인 항목을 대신 조회한 뒤 **조치 목록 중에서만** 제안합니다.
  그래서 확인 항목은 봇 도구로 실행할 수 있는 형태(PromQL, LogQL, k8s 조회)로 적습니다.
- 장애 후 포스트모템에서 "문서에 없던 상황"이 나오면 해당 Runbook을 고칩니다.

## 목록 (critical)

| 알람 | 의미 |
| --- | --- |
| [AuctionEngineDown](AuctionEngineDown.md) | 경매 엔진이 응답하지 않음 (Pod 없음/다운/멈춤) |
| [AuctionEngineHigh5xxRate](AuctionEngineHigh5xxRate.md) | 전체 API 5xx 비율 5% 초과 |
| [BidEndpoint5xxRate](BidEndpoint5xxRate.md) | 입찰 API 5xx 비율 2% 초과 |
| [DbConnectionAcquireTimeout](DbConnectionAcquireTimeout.md) | 엔진이 DB 커넥션을 얻지 못해 요청 실패 |
| [KubeStatefulSetReplicasUnavailable](KubeStatefulSetReplicasUnavailable.md) | DB(auction-db)가 Ready 아님 |
| [NodeDiskSpaceCritical](NodeDiskSpaceCritical.md) | 서버 디스크 여유 5% 미만 |

warning 알람은 알람 메시지의 description을 우선 참고합니다 (Runbook은 critical부터 작성).

## 작성 형식

새 Runbook은 아래 형식을 지킵니다. 트리아지 봇이 이 구조를 기준으로 읽습니다.

```markdown
---
alert: <알람명>            # 알람 규칙의 alert와 같아야 한다
severity: critical
service: <service 라벨>
---

# <알람명>

## 의미와 영향      - 무엇이 깨졌고 사용자에게 어떤 영향이 있는지
## 확인            - C1, C2 ... 순서대로. 각 항목에 [도구] 또는 [사람] 표시
## 조치            - A, B, C ... 각 항목에 "조건 / 실행 / 주의"
## 해소 확인        - 조치 후 정상인지 보는 방법
## 관련            - 함께 뜨는 알람, 다른 Runbook
```

### 확인 항목 표시

| 표시 | 의미 | 예 |
| --- | --- | --- |
| `[도구: query_prometheus]` | PromQL 조회 | 에러율, 커넥션 풀 |
| `[도구: query_loki]` | LogQL 조회 | 에러 로그 |
| `[도구: k8s_status]` | Pod/이벤트/Deployment/StatefulSet 상태 조회 (읽기 전용) | Pod 재시작, 종료 사유 |
| `[도구: rollout_history]` | Deployment의 ReplicaSet별 이미지 태그(커밋 SHA)와 생성 시각 | 최근 배포 여부 |
| `[사람]` | 서버 셸이나 DB 접속이 필요해 사람만 확인 가능 | `df`, `psql` |

봇 도구는 모두 **읽기 전용**입니다. 조치(롤백, 재시작, 삭제 등)는 항상 사람이 실행합니다.

### 공통으로 쓰는 값

- 네임스페이스: 앱 `auction`, 모니터링 `monitoring`
- 엔진 로그는 JSON(logstash 형식)입니다: `{namespace="auction", service="auction-engine"} | json | level="ERROR"`
  - 주요 필드: `level`, `message`, `logger_name`, `stack_trace`
- 이미지 태그 = 배포된 커밋 SHA (`ghcr.io/threeroun/<앱>:<SHA>`)
- 명령은 서버에서 kubeconfig가 설정된 계정으로 실행합니다 ([k8s/README.md](../../k8s/README.md))

## 공통 원칙

1. **복구가 먼저, 원인 분석은 나중**: 원인이 명확하지 않아도 조치 조건에 맞으면 먼저 복구합니다.
2. **증거부터 남긴다**: 재시작이나 롤백 전에 로그 시각, `kubectl describe` 결과, 스레드 덤프를 남깁니다.
   재시작하면 메모리 상태와 이전 컨테이너 로그가 사라집니다.
3. **DB 데이터는 건드리지 않는다**: PVC 삭제, DB 데이터 디렉터리 파일 삭제는 어떤 Runbook에서도 하지 않습니다.
   자동 백업이 없으므로 위험한 DB 작업 전에는 `pg_dump`를 먼저 받습니다.
4. **공유**: 조치를 시작하면 Discord 알림 스레드에 "누가 / 무엇을 / 언제" 했는지 남깁니다.
