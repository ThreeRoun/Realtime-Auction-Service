"""여러 컨테이너 로그를 시간순으로 엮어 장애 원인 분석 준비 (1단계: 수집 -> 정규화 -> 묶기 -> 타임라인)

사용 예시:
    python logging/ai-log-analyzer/log_correlator.py --minutes 15
    python logging/ai-log-analyzer/log_correlator.py --service realtime-relay auction-engine --minutes 30
"""
import argparse
import json
import os
import re
import urllib.parse
import urllib.request
from datetime import datetime

DEFAULT_SERVICES = ["realtime-relay", "auction-engine"]

# 서비스마다 레벨 대소문자가 다르므로 정규화
LEVEL_FILTER = '| json | level=~"(?i)warn|error|fatal"'

# 밖(화면과 클로드)으로 나가기 전에 마스킹할 값들. ai_monitor.py의 scrub() 규칙을 참고해 줄임
_MASKS = [
    (re.compile(r"eyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]*"), "[JWT]"),                  # JWT 토큰
    (re.compile(r"(?i)bearer\s+[\w.+/=-]{8,}"), "Bearer [REDACTED]"),           # 인증 헤더
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"), "[EMAIL]"),                       # 이메일
    (re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b"), "[IP]"),                       # IPv4 주소
    (re.compile(r"(?i)(password|secret|api[_-]?key)(\"?\s*[:=]\s*\"?)[^\s\",}]+"), r"\1\2[REDACTED]"),
]

def mask(text: str) -> str:
    """로그 문자열에서 개인정보, 비밀값을 가림"""
    for pattern, replacement in _MASKS:
        text = pattern.sub(replacement, text)
    return text

def query_loki(loki_url:str, service: str, start_ns: int, end_ns:int, limit: int) -> list:
    """한 서비스의 WARN 이상 로그를 Loki에서 시간 순서대로(오래된 것부터) 가져온다."""
    params = urllib.parse.urlencode({
        "query": f'{{service="{service}"}} {LEVEL_FILTER}',
        "start": str(start_ns),
        "end": str(end_ns),
        "limit": str(limit),
        "direction": "forward", # 오래된 것부터 가져오기(장애의 처음이 중요하기에)
    })
    request = urllib.request.Request(f"{loki_url}/loki/api/v1/query_range?{params}")
    # 그라파나 프록시로 k8s Loki를 볼 때만 토큰을 붙임 (로컬 Loki는 인증 없음)
    token = os.environ.get("GRAFANA_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=10) as response:
        data = json.load(response)
    if data.get("status") != "success":
        raise RuntimeError(f"Loki 조회 실패: {service}")
    return data["data"]["result"]

def normalize(service: str, ts_ns: str, line: str) -> dict:
    """서비스마다 다른 로그 형식을 같은 필드로 정규화
    relay(pino): level, event, message, err / engine(logstash): level, logger_name, message, stack_trace
    """
    try:
        entry = json.loads(line)
    except ValueError:
        entry = {"message": line}
    stack_head = str(entry.get("stack_trace", "")).split("\n")[0]
    return {
        "ts": int(ts_ns),
        "service": service,
        "level": str(entry.get("level", "?")).upper(),
        # relay는 event, engine은 logger_name의 클래스 이름만 (패키지 경로는 길어서 생략)
        "kind": str(entry.get("event") or str(entry.get("logger_name", "-")).rsplit(".",1)[-1]),
        "message": mask(str(entry.get("message") or entry.get("msg") or ""))[:200],
        "detail": mask(str(entry.get("err") or stack_head))[:200],
    }

def _pattern(text: str) -> str:
    """숫자만 다른 같은 종류의 로그를 하나로 묶기 위한 키 (ex. 포트 번호, 건수)"""
    return re.sub(r"\d+", "N", text)[:120]

def compress(entries: list) -> list:
    """같은 서비스의 같은 로그는 하나로 묶고, 횟수와 처음/마지막 시각만 남긴다."""
    groups: dict = {}
    for e in entries:
        key = (e["service"], e["level"], e["kind"], _pattern(e["message"]), _pattern(e["detail"]))
        group = groups.setdefault(key, {**e, "count": 0, "first": e["ts"], "last": e["ts"]})
        group["count"] += 1
        group["first"] = min(group["first"], e["ts"])
        group["last"] = max(group["last"], e["ts"])
    # 처음 나타난 시각 순서 = 타임라인. 가장 먼저 나온 이상 신호가 근본 원인 후보가 된다
    return sorted(groups.values(), key=lambda g: g["first"])


def _clock(ts_ns: int) -> str:
    return datetime.fromtimestamp(ts_ns / 1e9).astimezone().strftime("%H:%M:%S")


def render_timeline(groups: list) -> str:
    """사람(그리고 다음 단계에서 Claude)이 읽을 타임라인 텍스트."""
    lines = []
    for g in groups:
        span = _clock(g["first"]) if g["count"] == 1 else f'{_clock(g["first"])}~{_clock(g["last"])}'
        detail = f' ({g["detail"]})' if g["detail"] else ""
        lines.append(f'{span} | {g["service"]} | {g["level"]} | {g["kind"]} | {g["message"]}{detail} | {g["count"]}회')
    return "\n".join(lines) or "(해당 구간에 WARN 이상 로그 없음)"


def main() -> None:
    parser = argparse.ArgumentParser(description="여러 컨테이너 로그를 시간순 타임라인으로 엮는다")
    parser.add_argument("--loki-url", default=os.environ.get("LOKI_URL", "http://localhost:3100"))
    parser.add_argument("--services", nargs="+", default=DEFAULT_SERVICES)
    parser.add_argument("--minutes", type=int, default=15, help="지금부터 몇 분 전까지 볼지")
    parser.add_argument("--limit", type=int, default=500, help="서비스별 최대 로그 줄 수")
    args = parser.parse_args()

    end_ns = int(datetime.now().timestamp() * 1e9)
    start_ns = end_ns - args.minutes * 60 * 1_000_000_000

    entries = []
    for service in args.services:
        try:
            streams = query_loki(args.loki_url, service, start_ns, end_ns, args.limit)
        except OSError as exc:  # 연결 실패, 타임아웃 (URLError도 OSError의 한 종류)
            raise SystemExit(f"[오류] Loki에 연결하지 못했다 ({args.loki_url}): {exc}")
        for stream in streams:
            for ts_ns, line in stream["values"]:
                entries.append(normalize(service, ts_ns, line))

    groups = compress(entries)
    print(f"최근 {args.minutes}분, 서비스 {', '.join(args.services)}: 로그 {len(entries)}줄 -> {len(groups)}개로 묶음\n")
    print(render_timeline(groups))


if __name__ == "__main__":
    main()