# AI 모니터 (경매엔진)

Prometheus 지표와 Loki 로그를 주기적으로 모아서, 이상이 의심되면 Claude API에게 "정말 이상인지, 왜 그런지"를
판단시키고, 이상이면 GitHub 이슈로 자동 등록하는 스크립트다. 장애 알림 봇(Alertmanager → Discord)과 별개로,
경매엔진의 비즈니스 지표(입찰 수, 거절 사유, 연장, 낙찰/유찰)를 도메인 지식과 함께 해석하는 데 초점을 둔다.

## 동작 흐름

1. **수집**: Prometheus에서 최근 N분(기본 15분) 지표와 직전 60분 기준선, Loki에서 ERROR/WARN 로그를 가져온다.
2. **1차 규칙 필터**: 5xx, ERROR 로그, 입찰 거절 급증, 느린 p99 같은 수상한 신호가 하나도 없으면 AI를 부르지 않고 끝낸다(비용·소음 절약).
3. **AI 판단**: 수상한 신호가 있으면 Claude가 지표·로그 요약을 보고 `anomaly`, 심각도, 근거, 의심 원인, 권장 조치를 JSON으로 돌려준다.
4. **이슈 등록**: 이상이고 심각도가 `warning` 이상이면 `ai-monitor` 라벨의 이슈를 만든다. 이미 열린 이슈가 있으면 새로 만들지 않고 코멘트를 달며, 쿨다운(기본 60분) 중이면 AI 호출 없이 건너뛴다.

## 사용법

```bash
python -m venv .venv
.venv/Scripts/pip install -r monitoring/ai-monitor/requirements.txt   # macOS/Linux는 .venv/bin/pip

# 저장소 루트의 .env (gitignore 처리됨)에 키를 한 줄로 넣는다
#   ANTHROPIC_API_KEY=...

# 로컬 스택(docker compose -f docker-compose.yml -f docker-compose.logging.yml -f docker-compose.monitoring.yml up -d)이 떠 있는 상태에서
python monitoring/ai-monitor/ai_monitor.py                 # 드라이런: 이슈 본문만 미리보기
python monitoring/ai-monitor/ai_monitor.py --create-issue  # 실제로 GitHub 이슈 등록
python monitoring/ai-monitor/ai_monitor.py --loop-minutes 15 --create-issue   # 15분마다 반복
```

테스트: `python -m unittest discover -s monitoring/ai-monitor`

## 주요 옵션

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `--prometheus-url` / `--loki-url` | `localhost:9090` / `localhost:3100` | 환경변수 `PROMETHEUS_URL` / `LOKI_URL` 로도 지정 |
| `--window-minutes` | 15 | 분석할 최근 구간 |
| `--create-issue` | 꺼짐 | 켜면 실제 이슈 등록 (끄면 드라이런) |
| `--repo` | 팀 레포 | 이슈를 올릴 레포 (테스트는 개인 레포로) |
| `--min-severity` | warning | 이 심각도 이상일 때만 이슈 등록 |
| `--cooldown-minutes` | 60 | 열린 이슈가 이 시간 안에 갱신됐으면 건너뜀 |
| `--always-ask` | 꺼짐 | 규칙 필터를 무시하고 항상 AI에게 묻는다 (시연용) |
| `--model` | `claude-opus-5-5` | 환경변수 `AI_MONITOR_MODEL` 로도 지정 |

## 보안

- API 키는 `.env` 또는 환경변수 `ANTHROPIC_API_KEY`에서만 읽는다. SDK가 환경변수를 직접 읽으므로 코드가 키 값을 다루지 않고, 출력·저장하지 않는다.
- AI로 보내는 텍스트와 이슈로 올리는 텍스트, 화면 출력은 모두 `scrub()`을 거친다. 키, JWT, 이메일, 웹훅 URL, `password=` 류를 가리고 사용자 UUID는 앞 8자리만 남긴다.
- 이 레포는 공개 레포라서 이슈에는 로그 원문을 싣지 않고 AI 요약만 싣는다. AI가 만든 문장의 링크, 이미지, `@멘션`도 제거한다.
- `output/` 폴더에 매 실행의 수집 결과와 AI 응답이 남는다(gitignore 처리됨, 마스킹된 상태).

## 한계

- Prometheus `increase()`는 막 생성된 시리즈(앱 재시작, 첫 요청 직후)의 증가분을 과소 집계할 수 있다. 프롬프트에도 이 한계를 알려두었다.
- 지금은 로컬 실행용이다. 클러스터 안에서 주기 실행하려면 Prometheus/Loki 주소(`monitoring` 네임스페이스의 `prometheus`, `loki` 서비스)와 `ANTHROPIC_API_KEY` Secret이 필요하다.
