"""query_prometheus: PromQL 범위 조회 결과를 시계열마다 요약(최근값/최소/최대 + 샘플 몇 개)해서 돌려준다."""
import time

from . import http
from .http import ToolError
from .timefmt import kst

MAX_QUERY_LENGTH = 2000
MAX_MINUTES = 180
MAX_SERIES = 15
POINTS_PER_SERIES = 10


def _num(value: str) -> str:
    try:
        number = float(value)
    except ValueError:
        return value
    if number != number:          # NaN
        return "NaN"
    if number == int(number) and abs(number) < 1e15:
        return str(int(number))
    return f"{number:.4g}"


def _labels(metric: dict) -> str:
    name = metric.get("__name__", "")
    rest = ", ".join(f'{k}="{v}"' for k, v in sorted(metric.items()) if k != "__name__")
    return f"{name}{{{rest}}}" if rest or not name else name


def query_range(base_url: str, promql: str, minutes: int = 30) -> str:
    promql = (promql or "").strip()
    if not promql:
        raise ToolError("promql이 비어 있음")
    if len(promql) > MAX_QUERY_LENGTH:
        raise ToolError(f"promql이 너무 김 (최대 {MAX_QUERY_LENGTH}자)")
    minutes = max(1, min(int(minutes), MAX_MINUTES))
    end = time.time()
    step = max(15, minutes * 60 // 30)       # 시계열당 약 30개 점
    body = http.get_json(f"{base_url}/api/v1/query_range", {
        "query": promql, "start": f"{end - minutes * 60:.0f}", "end": f"{end:.0f}", "step": str(step)})
    if body.get("status") != "success":
        raise ToolError(f"Prometheus 오류: {body.get('error', body)}")

    series = body.get("data", {}).get("result", [])
    header = f"최근 {minutes}분, {step}초 간격, 시계열 {len(series)}개"
    if not series:
        return header + "\n(결과 없음 - 지표 이름/라벨이 맞는지, 해당 기간에 데이터가 있는지 확인)"

    lines = [header]
    for item in series[:MAX_SERIES]:
        values = item.get("values", [])
        numbers = [float(v) for _, v in values if v not in ("NaN", "+Inf", "-Inf")]
        lines.append(f"- {_labels(item.get('metric', {}))}")
        if not values:
            continue
        summary = f"  최근={_num(values[-1][1])} ({kst(values[-1][0], with_date=False)})"
        if numbers:
            summary += f", 최소={_num(str(min(numbers)))}, 최대={_num(str(max(numbers)))}"
        lines.append(summary)
        stride = max(1, len(values) // POINTS_PER_SERIES)
        sampled = values[::stride][-POINTS_PER_SERIES:]
        # 샘플 간격이 1분보다 짧으면 초까지 표시 (같은 분이 반복돼 보이지 않게)
        width = 8 if step * stride < 60 else 5
        lines.append("  추이: " + ", ".join(f"{kst(t, with_date=False)[:width]} {_num(v)}" for t, v in sampled))
    if len(series) > MAX_SERIES:
        lines.append(f"(시계열 {len(series) - MAX_SERIES}개 생략 - sum by (...)로 묶거나 라벨로 좁혀서 다시 조회)")
    return "\n".join(lines)
