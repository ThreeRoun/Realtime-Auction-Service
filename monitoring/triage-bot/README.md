# 🔎 triage-bot (장애 트리아지 봇)

Alertmanager의 **critical 알람**을 webhook으로 받아, 분석 결과를 Discord에 게시하는 봇입니다.
k3s 클러스터의 `monitoring` 네임스페이스에서 동작합니다 (`k8s/monitoring/triage-bot.yaml`).

> 알람 수신 → 중복 제거 → 읽기 전용 상태 조회 → **Claude 분석** → Discord 게시.
> Claude가 Runbook의 확인 절차를 조회 도구로 실행해 판단, 추정 원인, 근거, 권장 조치(Runbook 항목 중에서만)를 정리합니다.
> 조치는 실행하지 않습니다. API 키가 없거나 분석이 실패해도 알람 요약, Runbook 링크, 상태 조회 결과는 게시합니다.
> 로컬 compose용 [ai-monitor](../ai-monitor/README.md)(주기적으로 지표/로그를 직접 조회)와는 별개입니다.

## 동작

```text
Prometheus ─▶ Alertmanager ─┬─▶ Discord (기존 알람 메시지, 그대로)
                            └─▶ triage-bot  POST /alerts   (critical만, continue: true)
                                  1) 바로 200 응답 후 큐에 넣음 (Alertmanager를 기다리게 하지 않음)
                                  2) firing + critical만 남김 (resolved는 무시)
                                  3) 중복 제거: 같은 알람 그룹은 30분 동안 다시 분석하지 않음
                                     (그룹에 새 알람이 추가되면 다시 분석), 하루(KST) 20회 상한
                                  4) 워커 1개가 순서대로 처리: 알람 종류별 상태 조회 → Claude 분석(도구 호출)
                                     → Discord 게시 ("🔎 트리아지: <알람명>")
```

- 봇이 죽거나 느려도 기존 Discord 알람은 그대로 갑니다 (Alertmanager에서 별도 경로).
- 봇으로의 전송이 계속 실패하면 `AlertmanagerNotificationsFailing`(warning) 알람이 뜹니다.
- Discord webhook은 Alertmanager와 같은 `alertmanager-discord` Secret을 씁니다 (같은 채널에 게시). 매 게시마다 파일을 다시 읽으므로 Secret을 바꾸면 재시작 없이 반영됩니다.
- 메시지의 멘션(`@everyone` 등)은 막혀 있습니다 (`allowed_mentions`).
- NetworkPolicy로 Alertmanager와 Prometheus만 봇에 접근할 수 있습니다.

## Claude 분석

`triage_bot/claude.py`. 알람 1건(그룹)마다 다음 순서로 진행합니다.

1. 첫 메시지에 알람 내용, 해당 알람의 Runbook 전문, 아래 "메시지에 붙는 현재 상태" 조회 결과를 넣습니다.
2. Claude가 Runbook의 `[도구: x]` 확인 항목을 아래 조회 도구로 실행합니다. `[사람]` 항목은 "사람이 확인할 것"으로 남깁니다.
3. 정해진 JSON 형식(structured output)으로 결과를 받아 Discord 메시지로 만듭니다.

| 메시지 항목 | 내용 |
| --- | --- |
| 판단 | 한 문장 결론 |
| 추정 원인 | 가장 가능성 높은 원인 + 확신도(높음/보통/낮음) |
| 근거 | 조회로 확인한 사실 (최대 6개) |
| 권장 조치 | Runbook "조치"의 A 항목 중에서만, 조건 충족 근거와 함께 (최대 4개) |
| 사람이 확인할 것 | 도구로 확인할 수 없는 항목 (최대 4개) |
| 하단 | 답한 모델, 도구 호출 수, 소요 시간 |

- **모델**: `claude-opus-5-5`, effort `medium` (환경변수로 변경 가능).
- **비용/시간 상한**: 분석 1건당 도구 호출 12회, 최대 10턴, 300초. 하루(KST) 분석 횟수는 `DAILY_LIMIT`(20)로 제한됩니다.
  같은 앞부분(도구 정의, 시스템 프롬프트, 앞선 대화)은 prompt caching으로 다시 과금되지 않게 합니다.
  토큰 사용량은 `/metrics`의 `triage_bot_claude_tokens_total`로 볼 수 있습니다.
- **거절 대비**: 모델이 정책상 응답을 거절하면 서버 측 fallback(`fallbacks: "default"`)이 다른 모델로 다시 실행합니다.
  그래도 거절되면 "자동 분석 실패"로 게시합니다. fallback이 일어나면 하단에 실제로 답한 모델이 표시됩니다.
- **안전장치**: 도구는 모두 읽기 전용이고 결과는 마스킹됩니다. 로그 안의 문장은 지시로 따르지 않도록 시스템 프롬프트에 적어 두었습니다.
  Claude 출력도 다시 마스킹하고, Discord 멘션은 막혀 있습니다.
- **실패 시**: API 키 없음, API 오류, 시간 초과, 형식 오류 등은 "자동 분석 실패 (사유)"로 표시하고 나머지 내용은 그대로 게시합니다.
- API 키는 분석할 때마다 파일에서 읽으므로, Secret을 바꾸면 재시작 없이 반영됩니다.

## 조회 도구 (읽기 전용)

Runbook의 `[도구: ...]` 표시와 같은 이름입니다. Claude가 이 도구들로 Runbook의 확인 절차를 실행합니다.

| 도구 | 하는 일 | 제한 |
| --- | --- | --- |
| `query_prometheus` | PromQL 범위 조회 → 시계열별 최근값/최소/최대/추이 | 최대 180분, 시계열 15개 |
| `query_loki` | LogQL 조회 (최신순). JSON 로그는 level/message/예외 첫 줄/마지막 `Caused by:`(근본 원인)로 줄임. 지표 쿼리도 가능 | `namespace="auction"` 또는 `"monitoring"` 조건 필수, 최대 100줄 |
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

알람 종류에 따라 정해진 조회를 실행해 메시지에 붙입니다 (`triage_bot/snapshot.py`). Claude에게도 같은 결과를 넘깁니다.
Claude 분석이 없거나 실패해도 첫 확인은 메시지만 보고 할 수 있게 하려는 것입니다.

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
| `GET /metrics` | Prometheus 지표 (아래) |

| 지표 | 설명 |
| --- | --- |
| `triage_bot_webhooks_received_total` | webhook 수신 횟수 |
| `triage_bot_jobs_total{result}` | `posted`, `failed`, `skipped_cooldown`, `skipped_daily_limit`, `skipped_no_target`, `dropped_queue_full` |
| `triage_bot_claude_analyses_total{status}` | `ok`, `failed`, `disabled`(API 키 없음) |
| `triage_bot_claude_tokens_total{type}` | `input`, `output`, `cache_creation_input`, `cache_read_input` |
| `triage_bot_claude_tool_calls_total` | Claude가 실행한 조회 도구 호출 수 |
| `triage_bot_queue_size`, `triage_bot_job_duration_seconds` | 대기 작업 수, 처리 시간 |

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
| `ANTHROPIC_API_KEY_FILE` | `/etc/triage-bot/anthropic/api-key` | API 키가 든 파일. 없거나 비어 있으면 Claude 분석을 끈다 |
| `CLAUDE_MODEL` | `claude-opus-5-5` | |
| `CLAUDE_EFFORT` | `medium` | `low` / `medium` / `high` - 높을수록 깊게 분석하지만 느리고 비쌈 |
| `CLAUDE_MAX_TOOL_CALLS` | `12` | 분석 1건당 도구 호출 상한 |
| `CLAUDE_TIMEOUT_SECONDS` | `300` | 분석 1건 전체 시간 상한 |

중복 제거와 일일 상한 상태는 메모리에만 있어서, 봇이 재시작되면 초기화됩니다 (그래서 replica 1개).

## 로컬 실행과 테스트

Python 3.12. 의존성은 Claude 분석용 `anthropic` SDK 하나입니다 (`requirements.txt`).
단위 테스트는 가짜 API 클라이언트를 써서 실제 API를 호출하지 않습니다.

```bash
# 단위 테스트 (CI: ci-baseline.yml의 triage-bot-test)
pip install -r monitoring/triage-bot/requirements.txt
python -m unittest discover -s monitoring/triage-bot/tests -v

# 로컬 실행 (Discord에 보내지 않고 로그로 출력, k8s 조회는 클러스터 밖이라 "오류"로 표시됨)
# ANTHROPIC_API_KEY_FILE을 지정하지 않으면 Claude 분석 없이 동작한다 (지정하면 실제 API 호출 - 비용 발생)
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

## 서버 설정

Claude 분석을 켜려면 서버에서 API 키 Secret을 한 번 만듭니다. 레포와 CI에는 키를 넣지 않습니다.

```bash
# 키가 셸 기록에 남지 않게 입력받아서 만든다
read -rs ANTHROPIC_KEY && kubectl -n monitoring create secret generic triage-bot-anthropic \
  --from-literal=api-key="$ANTHROPIC_KEY"; unset ANTHROPIC_KEY

# 봇이 이미 떠 있으면 Secret 볼륨이 붙도록 한 번 재시작 (처음 만들 때만 필요)
kubectl -n monitoring rollout restart deploy/triage-bot

# 확인: 시작 로그의 claude_enabled가 true
kubectl -n monitoring logs deploy/triage-bot | grep '"시작"'
```

- 키를 바꿀 때는 Secret만 바꾸면 됩니다 (1~2분 안에 파일이 갱신되고, 분석할 때마다 다시 읽음):
  `kubectl -n monitoring create secret generic triage-bot-anthropic --from-literal=api-key="..." --dry-run=client -o yaml | kubectl apply -f -`
- 분석을 끄려면 Secret을 지웁니다 (`kubectl -n monitoring delete secret triage-bot-anthropic` 후 재시작).
- 봇 Pod에서 `api.anthropic.com`(HTTPS 443)으로 나가는 연결이 필요합니다.

## 배포

- 이미지: `ghcr.io/threeroun/triage-bot:<커밋 SHA>` - `deploy-baseline.yml`이 앱 이미지와 같이 빌드하고, `deploy-monitoring` job에서 SHA 태그로 적용합니다.
- 빌드 컨텍스트는 **레포 루트**입니다 (Runbook을 이미지에 넣기 위해): `docker build -f monitoring/triage-bot/Dockerfile .`
  보내는 파일은 `Dockerfile.dockerignore`의 허용 목록으로 제한합니다.
- GHCR pull Secret은 배포 job이 `auction` 네임스페이스의 `ghcr-secret`을 `monitoring`으로 복사합니다 (서버에서 따로 만들 필요 없음).
- 로그는 JSON 한 줄 형식이라 Loki에서 `{namespace="monitoring", service="triage-bot"} | json` 로 볼 수 있습니다.
