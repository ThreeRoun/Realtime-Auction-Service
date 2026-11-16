"""query_loki: LogQL로 로그를 조회한다. 로그 쿼리는 최신순 줄 목록, 지표 쿼리(count_over_time 등)는 시계열 요약.

- auction / monitoring 네임스페이스만 조회할 수 있다 (쿼리에 namespace 조건 필수).
- JSON 로그(엔진, 봇)는 level / message / 예외 첫 줄만 뽑아 줄인다.
"""
import json
import re
import time

from . import http
from .http import ToolError
from .prometheus import _labels, _num
from .timefmt import kst

MAX_QUERY_LENGTH = 1000
MAX_MINUTES = 180
MAX_LINES = 100
LINE_LIMIT = 400   # 메시지 + 예외 첫 줄(120자) + 근본 원인(150자)이 들어가게
_NAMESPACE = re.compile(r'namespace\s*(=|=~)\s*"(auction|monitoring|auction\|monitoring|monitoring\|auction)"')


def _clip(text: str, size: int) -> str:
    return text if len(text) <= size else text[:size] + "…"


def _compact(line: str) -> str:
    try:
        entry = json.loads(line)
    except ValueError:
        return line.strip()
    if not isinstance(entry, dict):
        return line.strip()
    level = entry.get("level") or entry.get("severity") or ""
    message = entry.get("message") or entry.get("msg") or ""
    text = f"{level} {message}".strip() or line.strip()
    stack = entry.get("stack_trace") or entry.get("error") or ""
    if stack:
        lines = str(stack).strip().splitlines()
        text += f" | {_clip(lines[0].strip(), 120)}"
        # 근본 원인은 보통 마지막 "Caused by:"에 있다 (예: UnknownHostException)
        causes = [l.strip() for l in lines[1:] if l.strip().startswith("Caused by:")]
        if causes:
            text += f" | {_clip(causes[-1], 150)}"
    return text


def query(base_url: str, logql: str, minutes: int = 30, limit: int = 50) -> str:
    logql = (logql or "").strip()
    if not logql:
        raise ToolError("logql이 비어 있음")
    if len(logql) > MAX_QUERY_LENGTH:
        raise ToolError(f"logql이 너무 김 (최대 {MAX_QUERY_LENGTH}자)")
    if not _NAMESPACE.search(logql):
        raise ToolError('스트림 선택자에 namespace="auction" 또는 namespace="monitoring" 조건이 필요함')
    minutes = max(1, min(int(minutes), MAX_MINUTES))
    limit = max(1, min(int(limit), MAX_LINES))
    end = time.time()
    body = http.get_json(f"{base_url}/loki/api/v1/query_range", {
        "query": logql, "start": f"{(end - minutes * 60) * 1e9:.0f}", "end": f"{end * 1e9:.0f}",
        "limit": str(limit), "direction": "backward", "step": str(max(15, minutes * 60 // 30))})
    if body.get("status") != "success":
        raise ToolError(f"Loki 오류: {body.get('error', body)}")

    data = body.get("data", {})
    result = data.get("result", [])
    if data.get("resultType") == "matrix":      # 지표 쿼리
        lines = [f"최근 {minutes}분, 시계열 {len(result)}개"]
        for item in result[:15]:
            values = item.get("values", [])
            last = _num(values[-1][1]) if values else "?"
            peak = _num(str(max(float(v) for _, v in values))) if values else "?"
            lines.append(f"- {_labels(item.get('metric', {}))}: 최근={last}, 최대={peak}")
        return "\n".join(lines) if result else lines[0] + "\n(결과 없음)"

    entries = []
    for stream in result:
        labels = stream.get("stream", {})
        source = labels.get("pod") or labels.get("service") or ""
        for ts, line in stream.get("values", []):
            entries.append((int(ts), source, line))
    entries.sort(reverse=True)
    entries = entries[:limit]
    header = f"최근 {minutes}분, {len(entries)}줄 (최신순{', 상한 도달 - 범위를 좁혀서 다시 조회' if len(entries) == limit else ''})"
    if not entries:
        return header + "\n(로그 없음)"
    lines = [header]
    for ts, source, line in entries:
        text = _compact(line)
        if len(text) > LINE_LIMIT:
            text = text[:LINE_LIMIT] + "…"
        lines.append(f"{kst(ts / 1e9, with_date=False)} [{source}] {text}")
    return "\n".join(lines)
