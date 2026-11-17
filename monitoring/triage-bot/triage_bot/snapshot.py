"""알람 종류에 따라 정해진 조회를 몇 개 실행해 "현재 상태"를 붙인다.

Claude 분석과 별개로, 분석이 없거나 실패해도 메시지만 보고 첫 확인을 할 수 있게 하는 용도다
(Runbook의 첫 확인 항목들과 같은 조회).
"""
from .alerts import Job
from .tools import Toolbox

MAX_CHECKS = 3
RESULT_LIMIT = 700
DISK_QUERY = ('1 - node_filesystem_avail_bytes{job="node-exporter", mountpoint="/"} '
              '/ node_filesystem_size_bytes{job="node-exporter", mountpoint="/"}')


def plan(job: Job) -> list[tuple[str, str, dict]]:
    """(제목, 도구 이름, 입력) 목록. 같은 조회는 한 번만."""
    checks: list[tuple[str, str, dict]] = []
    for alert in job.alerts:
        labels = alert.labels
        if labels.get("service") == "auction-engine":
            checks += [
                ("엔진 Pod", "k8s_status", {"namespace": "auction", "kind": "pods", "selector": "app=auction-engine"}),
                ("엔진 배포 이력", "rollout_history", {"namespace": "auction", "deployment": "auction-engine"}),
            ]
        if labels.get("statefulset") and labels.get("namespace") in ("auction", "monitoring"):
            name = labels["statefulset"]
            checks.append((f"{name} Pod", "k8s_status",
                           {"namespace": labels["namespace"], "kind": "pods", "selector": f"app={name}"}))
        if labels.get("service") == "node":
            checks.append(("서버 디스크 사용률(/)", "query_prometheus", {"promql": DISK_QUERY, "minutes": 60}))
    unique, seen = [], set()
    for title, tool, args in checks:
        key = (tool, tuple(sorted(args.items())))
        if key not in seen:
            seen.add(key)
            unique.append((title, tool, args))
    return unique[:MAX_CHECKS]


def collect(job: Job, toolbox: Toolbox) -> list[tuple[str, str]]:
    results = []
    for title, tool, args in plan(job):
        text, _ = toolbox.run(tool, args)
        if tool == "rollout_history":
            text = "\n".join(text.splitlines()[:4])       # 최근 3개 버전이면 충분
        if len(text) > RESULT_LIMIT:
            text = text[:RESULT_LIMIT] + "…"
        results.append((title, text))
    return results
