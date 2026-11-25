"""테스트용 트레이스 1개를 Alloy(OTLP/HTTP)로 보낸다. 앱 계측 전에 수집 경로(Alloy -> Tempo -> Grafana)를 확인하는 용도.

사용 예 (로그 스택이 떠 있는 상태에서, 저장소 루트에서):
    python3 logging/tempo/send_test_trace.py
    -> 출력된 trace_id를 Grafana(localhost:3001) Explore > Tempo에서 검색
"""
import json
import os
import sys
import time
import urllib.request

ENDPOINT = os.environ.get("OTLP_HTTP_ENDPOINT", "http://localhost:4318")


def _span(trace_id: str, span_id: str, parent_id: str, name: str, start_ns: int, end_ns: int) -> dict:
    """OTLP JSON 형식의 span 하나. trace_id/span_id는 16진수 문자열로 쓴다 (OTLP JSON 규칙)."""
    return {
        "traceId": trace_id,
        "spanId": span_id,
        "parentSpanId": parent_id,
        "name": name,
        "kind": 2,  # SERVER
        "startTimeUnixNano": str(start_ns),
        "endTimeUnixNano": str(end_ns),
        "attributes": [{"key": "test", "value": {"boolValue": True}}],
    }


def main() -> None:
    trace_id = os.urandom(16).hex()  # 32자리 16진수
    root_id, child_id = os.urandom(8).hex(), os.urandom(8).hex()
    now = time.time_ns()

    payload = {
        "resourceSpans": [{
            # service.name은 Grafana에서 서비스 이름으로 보이고, trace -> log 이동 때 Loki의 service 라벨로 쓰인다
            "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "trace-test"}}]},
            "scopeSpans": [{
                "scope": {"name": "send_test_trace"},
                "spans": [
                    # 부모 span(요청 전체 200ms) 안에 자식 span(작업 150ms)이 들어 있는 구조
                    _span(trace_id, root_id, "", "POST /api/bids (테스트)", now - 200_000_000, now),
                    _span(trace_id, child_id, root_id, "redis publish (테스트)", now - 180_000_000, now - 30_000_000),
                ],
            }],
        }],
    }

    request = urllib.request.Request(
        f"{ENDPOINT}/v1/traces",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            print(f"전송 완료 (HTTP {response.status}) trace_id={trace_id}")
    except OSError as exc:  # 연결 실패, 4xx/5xx(HTTPError도 OSError의 한 종류)
        sys.exit(f"[오류] {ENDPOINT} 로 전송하지 못했다: {exc}")


if __name__ == "__main__":
    main()