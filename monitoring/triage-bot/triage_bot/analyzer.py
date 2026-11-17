"""알람 분석. 지금은 수신 내용과 Runbook 링크만 정리한다 (Claude 분석은 다음 단계에서 이 자리에 붙인다)."""
from dataclasses import dataclass, field

from .alerts import KST, Job


@dataclass(frozen=True)
class Analysis:
    status: str                       # "pending" (분석 기능 없음) / 이후 "ok", "failed"
    summary: str
    details: list[str] = field(default_factory=list)
    runbooks: dict[str, str] = field(default_factory=dict)   # 알람명 -> URL


def analyze(job: Job) -> Analysis:
    details = []
    runbooks = {}
    for alert in job.alerts:
        started = alert.starts_at.astimezone(KST).strftime("%m-%d %H:%M:%S") if alert.starts_at else "?"
        summary = alert.annotations.get("summary") or alert.name
        details.append(f"{summary} (시작 {started})")
        if alert.annotations.get("runbook_url"):
            runbooks[alert.name] = alert.annotations["runbook_url"]
    return Analysis(
        status="pending",
        summary="알람을 수신했습니다. 자동 분석 기능은 준비 중이라 Runbook의 확인 절차를 따라 주세요.",
        details=details,
        runbooks=runbooks,
    )
