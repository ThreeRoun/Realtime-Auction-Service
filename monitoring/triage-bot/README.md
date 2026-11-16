# 🔎 triage-bot (장애 트리아지 봇)

Alertmanager의 **critical 알람**을 webhook으로 받아, 분석 결과를 Discord에 게시하는 봇입니다.
k3s 클러스터의 `monitoring` 네임스페이스에서 동작합니다 (`k8s/monitoring/triage-bot.yaml`).

> 현재는 **기본 골격** 단계입니다. 알람 수신 → 중복 제거 → Discord 게시까지 동작하고,
> 게시 내용은 알람 요약과 Runbook 링크입니다. 클러스터 조회 도구와 Claude 분석은 다음 단계에서 붙입니다.
> 로컬 compose용 [ai-monitor](../ai-monitor/README.md)(주기적으로 지표/로그를 직접 조회)와는 별개입니다.

## 동작

```text
Prometheus ─▶ Alertmanager ─┬─▶ Discord (기존 알람 메시지, 그대로)
                            └─▶ triage-bot  POST /alerts   (critical만, continue: true)
                                  1) 바로 200 응답 후 큐에 넣음 (Alertmanager를 기다리게 하지 않음)
                                  2) firing + critical만 남김 (resolved는 무시)
                                  3) 중복 제거: 같은 알람 그룹은 30분 동안 다시 분석하지 않음
                                     (그룹에 새 알람이 추가되면 다시 분석), 하루(KST) 20회 상한
                                  4) 워커 1개가 순서대로 처리 → Discord 게시 ("🔎 트리아지: <알람명>")
```

- 봇이 죽거나 느려도 기존 Discord 알람은 그대로 갑니다 (Alertmanager에서 별도 경로).
- 봇으로의 전송이 계속 실패하면 `AlertmanagerNotificationsFailing`(warning) 알람이 뜹니다.
- Discord webhook은 Alertmanager와 같은 `alertmanager-discord` Secret을 씁니다 (같은 채널에 게시). 매 게시마다 파일을 다시 읽으므로 Secret을 바꾸면 재시작 없이 반영됩니다.
- 메시지의 멘션(`@everyone` 등)은 막혀 있습니다 (`allowed_mentions`).
- NetworkPolicy로 Alertmanager와 Prometheus만 봇에 접근할 수 있습니다.

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

중복 제거와 일일 상한 상태는 메모리에만 있어서, 봇이 재시작되면 초기화됩니다 (그래서 replica 1개).

## 로컬 실행과 테스트

표준 라이브러리만 씁니다 (Python 3.12).

```bash
# 단위 테스트 (CI: ci-baseline.yml의 triage-bot-test)
python -m unittest discover -s monitoring/triage-bot/tests -v

# 로컬 실행 (Discord에 보내지 않고 로그로 출력)
cd monitoring/triage-bot
DRY_RUN=true python -m triage_bot

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
- GHCR pull Secret은 배포 job이 `auction` 네임스페이스의 `ghcr-secret`을 `monitoring`으로 복사합니다 (서버에서 따로 만들 필요 없음).
- 로그는 JSON 한 줄 형식이라 Loki에서 `{namespace="monitoring", service="triage-bot"} | json` 로 볼 수 있습니다.
