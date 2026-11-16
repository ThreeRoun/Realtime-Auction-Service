"""알람 분석: 수신 내용과 Runbook 링크를 정리하고, 정해진 상태 조회를 붙인 뒤 Claude 분석을 실행한다.
Claude 분석이 꺼져 있거나 실패해도 나머지(요약, Runbook 링크, 자동 조회 결과)는 그대로 게시한다."""
import logging
from dataclasses import dataclass, field

from . import snapshot
from .alerts import KST, Job
from .claude import ClaudeAnalyst, ClaudeError, ClaudeResult
from .masking import scrub
from .tools import Toolbox

log = logging.getLogger(__name__)

NO_ANALYSIS = "Runbook의 확인 절차를 따라 주세요."


@dataclass(frozen=True)
class Analysis:
    status: str                       # "ok" (Claude 분석 성공) / "disabled" (분석 꺼짐) / "failed" (분석 실패)
    summary: str
    details: list[str] = field(default_factory=list)
    runbooks: dict[str, str] = field(default_factory=dict)          # 알람명 -> URL
    snapshot: list[tuple[str, str]] = field(default_factory=list)   # (제목, 조회 결과)
    claude: ClaudeResult | None = None


def analyze(job: Job, toolbox: Toolbox | None = None, analyst: ClaudeAnalyst | None = None) -> Analysis:
    details = []
    runbooks = {}
    for alert in job.alerts:
        started = alert.starts_at.astimezone(KST).strftime("%m-%d %H:%M:%S") if alert.starts_at else "?"
        summary = alert.annotations.get("summary") or alert.name
        details.append(f"{summary} (시작 {started})")
        if alert.annotations.get("runbook_url"):
            runbooks[alert.name] = alert.annotations["runbook_url"]
    snap = snapshot.collect(job, toolbox) if toolbox else []
    base = {"details": details, "runbooks": runbooks, "snapshot": snap}

    if analyst is None or toolbox is None or not analyst.enabled():
        return Analysis(status="disabled", summary=f"알람을 수신했습니다. 자동 분석이 꺼져 있어 {NO_ANALYSIS}", **base)
    try:
        result = analyst.analyze(job, toolbox, snap)
    except ClaudeError as exc:
        reason = str(exc)
    except Exception as exc:     # API 오류, 네트워크 오류 등 - 분석 없이라도 게시는 한다
        log.exception("Claude 분석 중 예외", extra={"fields": {"job": job.job_id}})
        reason = scrub(f"{type(exc).__name__}: {exc}")[:200]
    else:
        return Analysis(status="ok", summary=result.finding.summary, claude=result, **base)
    return Analysis(status="failed", summary=f"자동 분석 실패 ({reason}). {NO_ANALYSIS}", **base)
