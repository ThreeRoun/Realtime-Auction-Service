# 멀티 컨테이너 로그 상관관계 분석

여러 서비스(realtime-relay, auction-engine 등)의 WARN 이상 로그를 Loki에서 모아 **시간순 타임라인**으로 엮고,
Claude API에게 "어느 서비스에서 먼저 문제가 시작됐고, 나머지는 그 결과인지"를 분석시키는 스크립트다.

경매엔진 AI 모니터(`monitoring/ai-monitor`)가 경매 엔진의 지표와 로그를 주기적으로 보는 것과 달리,
이 도구는 **서비스 사이의 로그를 엮어서 장애 원인을 좁히는 것**에 초점을 둔다.

## 동작 흐름

1. **수집**: 서비스마다 최근 N분(기본 15분)의 WARN/ERROR/FATAL 로그를 Loki에서 오래된 순으로 가져온다.
   레벨은 대소문자를 구분하지 않는다(relay 형식 통일 전 로그는 소문자).
2. **정규화**: 서비스마다 다른 로그 형식을 같은 필드로 맞춘다.

   | 필드 | realtime-relay (pino) | auction-engine (logstash) |
   | --- | --- | --- |
   | 종류 | `event` | `logger_name`의 클래스 이름 |
   | 메시지 | `message` (예전 형식은 `msg`) | `message` |
   | 상세 | `err` | `stack_trace` 첫 줄 |

3. **마스킹**: JWT, Bearer 토큰, 이메일, IP, `password=` 류 값을 가린다.
4. **묶기**: 같은 서비스의 같은 로그(숫자만 다른 것 포함)는 하나로 묶고 횟수, 처음/마지막 시각만 남긴다.
5. **타임라인**: 묶음을 처음 나타난 시각 순으로 정렬한다. 가장 먼저 나온 신호가 근본 원인 후보가 된다.
6. **AI 분석** (`--ask`): 타임라인을 Claude API에 보내고, 구조화된 출력(`output_config.format`)으로
   근본 원인 서비스, 인과 순서, 근거, 의심 원인, 확신도, 확인할 것을 받는다.
   분석할 로그가 없으면 API를 호출하지 않는다.

## 사용법

로컬 스택(`docker compose -f docker-compose.yml -f docker-compose.logging.yml up -d`)이 떠 있는 상태에서
저장소 루트에서 실행한다.

```bash
# 타임라인만 보기 (표준 라이브러리만 사용, API 키 불필요)
python3 logging/ai-log-analyzer/log_correlator.py --minutes 15

# Claude 분석까지 (anthropic 패키지 필요)
python3 -m venv .venv
.venv/bin/pip install -r logging/ai-log-analyzer/requirements.txt
.venv/bin/python logging/ai-log-analyzer/log_correlator.py --minutes 15 --ask
```

API 키는 저장소 루트의 `.env`(gitignore 처리됨)에 `ANTHROPIC_API_KEY=...` 한 줄로 넣는다.
경매엔진 AI 모니터와 같은 파일을 쓴다.

### k8s 로그 조회

k8s의 Loki는 외부에 열려 있지 않으므로 Grafana 데이터 소스 프록시를 거친다.
Grafana에서 Viewer 역할의 서비스 계정 토큰을 발급받아 환경변수로 넣는다(값이 셸 기록에 남지 않게 `read -s` 사용).

```bash
read -s GRAFANA_TOKEN
export GRAFANA_TOKEN
python3 logging/ai-log-analyzer/log_correlator.py \
  --loki-url http://<서버>/grafana/api/datasources/proxy/uid/loki --minutes 15
```

## 주요 옵션

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `--loki-url` | `http://localhost:3100` | 환경변수 `LOKI_URL`로도 지정 |
| `--services` | `realtime-relay auction-engine` | 함께 볼 서비스(Loki `service` 라벨) |
| `--minutes` | 15 | 지금부터 몇 분 전까지 볼지 |
| `--limit` | 500 | 서비스별 최대 로그 줄 수 |
| `--ask` | 꺼짐 | 켜면 Claude API로 원인 분석 |
| `--model` | `claude-haiku-5-5` | 환경변수 `AI_LOG_MODEL`로도 지정 |

## 실행 예시

로컬에서 `docker stop auction-redis` 후 입찰을 시도하고 실행한 결과(발췌).

```text
09:21:53 | auction-engine | WARN | ConnectionWatchdog | Cannot reconnect ... Connection refused | 1회
09:21:53~09:25:16 | realtime-relay | ERROR | redis_error | Redis 연결 오류 (getaddrinfo ENOTFOUND auction-redis) | 82회
09:22:34~09:25:14 | auction-engine | WARN | ConnectionWatchdog | Cannot reconnect ... No route to host | 10회
09:24:02 | auction-engine | ERROR | TransactionSynchronizationUtils | ... (QueryTimeoutException: Redis command timed out) | 1회

근본 원인 추정: auction-redis (확신도 medium)
근거:
  - realtime-relay도 같은 시각(09:21:53)부터 auction-redis 이름 해석 실패(ENOTFOUND)를 지속적으로 기록함
  - 두 서비스 모두 auction-redis 대상으로만 실패하고, 이는 서비스 구조상 Redis가 공통 통로라는 설명과 일치함
  - 09:24:02 트랜잭션 후처리 실패는 Redis 장애의 2차 영향으로 보임

토큰: 입력 1390 / 출력 1656 (claude-haiku-5-5)
```

## 한계

- 서비스 사이를 잇는 공통 ID(trace_id, event_id)가 없어서 **시간과 서비스 라벨로만** 엮는다.
  같은 시간대에 다른 장애가 겹치면 섞일 수 있다.
- AI 결과는 추정이다. 확신도와 근거를 보고 사람이 판단하며, 재시작이나 롤백 같은 조치는 하지 않는다.
- 배포 환경(compose, k3s) 정보가 프롬프트에 없어서, 확인할 것에 DNS, 방화벽 같은 일반적인 항목이 섞일 수 있다.
