# 🔎 triage-bot (장애 트리아지 봇)

Alertmanager의 **critical 알람**을 webhook으로 받아, 분석 결과를 Discord에 게시하는 봇입니다.
k3s 클러스터의 `monitoring` 네임스페이스에서 동작합니다 (`k8s/monitoring/triage-bot.yaml`).

> 현재 단계: 알람 수신 → 중복 제거 → **읽기 전용 상태 조회** → Discord 게시.
> 게시 내용은 알람 요약, Runbook 링크, 알람 종류별 현재 상태(Pod 상태, 배포 이력 등)입니다. Claude 분석은 다음 단계에서 붙입니다.
> 로컬 compose용 [ai-monitor](../ai-monitor/README.md)(주기적으로 지표/로그를 직접 조회)와는 별개입니다.

## 동작

```text
Prometheus ─▶ Alertmanager ─┬─▶ Discord (기존 알람 메시지, 그대로)
                            └─▶ triage-bot  POST /alerts   (critical만, continue: true)
                                  1) 바로 200 응답 후 큐에 넣음 (Alertmanager를 기다리게 하지 않음)
                                  2) firing + critical만 남김 (resolved는 무시)
                                  3) 중복 제거: 같은 알람 그룹은 30분 동안 다시 분석하지 않음
                                     (그룹에 새 알람이 추가되면 다시 분석), 하루(KST) 20회 상한
                                  4) 워커 1개가 순서대로 처리: 알람 종류별 상태 조회 → Discord 게시 ("🔎 트리아지: <알람명>")
```

- 봇이 죽거나 느려도 기존 Discord 알람은 그대로 갑니다 (Alertmanager에서 별도 경로).
- 봇으로의 전송이 계속 실패하면 `AlertmanagerNotificationsFailing`(warning) 알람이 뜹니다.
- Discord webhook은 Alertmanager와 같은 `alertmanager-discord` Secret을 씁니다 (같은 채널에 게시). 매 게시마다 파일을 다시 읽으므로 Secret을 바꾸면 재시작 없이 반영됩니다.
- 메시지의 멘션(`@everyone` 등)은 막혀 있습니다 (`allowed_mentions`).
- NetworkPolicy로 Alertmanager와 Prometheus만 봇에 접근할 수 있습니다.

## 조회 도구 (읽기 전용)

Runbook의 `[도구: ...]` 표시와 같은 이름입니다. 다음 단계에서 Claude가 이 도구들로 Runbook의 확인 절차를 실행합니다.

| 도구 | 하는 일 | 제한 |
| --- | --- | --- |
| `query_prometheus` | PromQL 범위 조회 → 시계열별 최근값/최소/최대/추이 | 최대 180분, 시계열 15개 |
| `query_loki` | LogQL 조회 (최신순). JSON 로그는 level, message, 예외 첫 줄, 마지막 `Caused by:`(근본 원인)로 줄임. relay 로그는 `[event]`와 `err`/`reason`도 표시. 지표 쿼리도 가능 | `namespace="auction"` 또는 `"monitoring"` 조건 필수, 최대 100줄 |
| `k8s_status` | `pods` / `events` / `deployments` / `statefulsets` 상태 요약 | `auction`, `monitoring` 네임스페이스만 |
| `rollout_history` | Deployment의 배포 이력 (ReplicaSet별 이미지 SHA, 생성 시각) | 최근 10개 |
| `get_runbook` | 이미지에 함께 넣은 `docs/runbooks/<알람명>.md` | |

- 모든 결과는 비밀값(API 키, JWT, webhook URL, 이메일 등)을 가리고 6000자로 자릅니다 (`triage_bot/masking.py`).
- 실패하면 `오류: ...` 문자열을 돌려줍니다 (Claude가 읽고 쿼리를 고칠 수 있게).
- k8s 권한(`triage-bot-read` Role)은 두 네임스페이스의 pods, events, deployments, replicasets, statefulsets **get/list만** 있습니다.
  로그(`pods/log`), `exec`, `secrets`, 쓰기 권한은 없습니다.

서버에서 봇과 같은 권한으로 도구를 직접 실행해 볼 수 있습니다:

```bash
kubectl -n monitoring exec deploy/triage-bot -- python -m triage_bot.tools list
kubectl -n monitoring exec deploy/triage-bot -- python -m triage_bot.tools k8s_status '{"namespace":"auction","kind":"pods"}'
kubectl -n monitoring exec deploy/triage-bot -- python -m triage_bot.tools rollout_history '{"namespace":"auction","deployment":"auction-engine"}'
```

### 메시지에 붙는 현재 상태

알람 종류에 따라 정해진 조회를 실행해 메시지에 붙입니다 (`triage_bot/snapshot.py`). Claude 분석이 없거나 실패해도 첫 확인은 메시지만 보고 할 수 있게 하려는 것입니다.

| 알람 | 조회 |
| --- | --- |
| `service="auction-engine"` | 엔진 Pod 상태, 엔진 배포 이력 |
| `statefulset` 라벨이 있음 (DB) | 해당 StatefulSet의 Pod 상태 |
| `service="node"` | 서버 디스크 사용률(`/`) |

## 엔드포인트

| 경로 | 용도 |
| --- | --- |
| `POST /alerts` | Alertmanager webhook (version 4 형식) |
| `GET /healthz` | liveness/readiness |
| `GET /metrics` | Prometheus 지표 (`triage_bot_webhooks_received_total`, `triage_bot_jobs_total{result}`, `triage_bot_queue_size`, `triage_bot_job_duration_seconds`) |

`triage_bot_jobs_total`의 `result`: `posted`, `failed`, `skipped_cooldown`, `skipped_daily_limit`, `skipped_no_target`, `dropped_queue_full`

## 설정 (환경변수)

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DISCORD_WEBHOOK_URL_FILE` | `/etc/triage-bot/discord/discord-webhook-url` | webhook URL이 든 파일 |
| `COOLDOWN_MINUTES` | `30` | 같은 알람 그룹 재분석 금지 시간 |
| `DAILY_LIMIT` | `20` | 하루(KST) 분석 횟수 상한 |
| `QUEUE_SIZE` | `20` | 처리 대기 큐 크기 (넘치면 버림) |
| `TARGET_SEVERITIES` | `critical` | 분석할 severity (쉼표 구분) |
| `DRY_RUN` | `false` | `true`면 Discord에 보내지 않고 로그로만 출력 |
| `PORT` | `8080` | |
| `PROMETHEUS_URL` | `http://prometheus:9090` | 조회 도구 대상 |
| `LOKI_URL` | `http://loki:3100` | 조회 도구 대상 |
| `RUNBOOK_DIR` | `/app/runbooks` | 이미지에 넣은 Runbook 위치 |

중복 제거와 일일 상한 상태는 메모리에만 있어서, 봇이 재시작되면 초기화됩니다 (그래서 replica 1개).

## 로컬 실행과 테스트

표준 라이브러리만 씁니다 (Python 3.12).

```bash
# 단위 테스트 (CI: ci-baseline.yml의 triage-bot-test)
python -m unittest discover -s monitoring/triage-bot/tests -v

# 로컬 실행 (Discord에 보내지 않고 로그로 출력, k8s 조회는 클러스터 밖이라 "오류"로 표시됨)
cd monitoring/triage-bot
DRY_RUN=true RUNBOOK_DIR=../../docs/runbooks python -m triage_bot

# 다른 터미널에서 테스트 알람 전송
curl -s -XPOST localhost:8080/alerts -H 'Content-Type: application/json' -d '{
  "version": "4", "groupKey": "test", "status": "firing",
  "alerts": [{"status": "firing", "fingerprint": "t1",
    "labels": {"alertname": "AuctionEngineDown", "severity": "critical"},
    "annotations": {"summary": "테스트", "runbook_url": "https://example.com/runbook"},
    "startsAt": "2026-11-16T03:00:00Z"}]}'
```

## 배포

- 이미지: `ghcr.io/threeroun/triage-bot:<커밋 SHA>` - `deploy-baseline.yml`이 앱 이미지와 같이 빌드하고, `deploy-monitoring` job에서 SHA 태그로 적용합니다.
- 빌드 컨텍스트는 **레포 루트**입니다 (Runbook을 이미지에 넣기 위해): `docker build -f monitoring/triage-bot/Dockerfile .`
  보내는 파일은 `Dockerfile.dockerignore`의 허용 목록으로 제한합니다.
- GHCR pull Secret은 배포 job이 `auction` 네임스페이스의 `ghcr-secret`을 `monitoring`으로 복사합니다 (서버에서 따로 만들 필요 없음).
- 로그는 JSON 한 줄 형식이라 Loki에서 `{namespace="monitoring", service="triage-bot"} | json` 로 볼 수 있습니다.
