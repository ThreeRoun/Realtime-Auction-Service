"""Alertmanager webhook payload(version 4)를 분석 작업(Job) 단위로 바꾼다.

https://prometheus.io/docs/alerting/latest/configuration/#webhook_config
"""
import re
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
_FRACTION = re.compile(r"(\.\d+)")


class PayloadError(ValueError):
    """Alertmanager webhook 형식이 아닌 요청."""


@dataclass(frozen=True)
class Alert:
    status: str
    labels: dict[str, str]
    annotations: dict[str, str]
    starts_at: datetime | None
    fingerprint: str

    @property
    def name(self) -> str:
        return self.labels.get("alertname", "unknown")

    @property
    def severity(self) -> str:
        return self.labels.get("severity", "")


@dataclass(frozen=True)
class Job:
    """알람 그룹 하나(= Alertmanager 알림 한 번)에 대한 분석 작업."""
    group_key: str
    alerts: tuple[Alert, ...]
    common_labels: dict[str, str]
    received_at: datetime
    job_id: str = field(default_factory=lambda: uuid.uuid4().hex[:8])

    @property
    def title(self) -> str:
        names = sorted({a.name for a in self.alerts})
        return names[0] if len(names) == 1 else f"{names[0]} 외 {len(names) - 1}건"

    @property
    def fingerprints(self) -> frozenset[str]:
        return frozenset(a.fingerprint for a in self.alerts)


def _parse_time(value: object) -> datetime | None:
    if not isinstance(value, str) or not value or value.startswith("0001-"):
        return None
    # Alertmanager는 나노초(소수점 9자리)까지 보내므로 6자리로 자른다: 2026-11-16T03:18:41.123456789Z
    value = _FRACTION.sub(lambda m: m.group(1)[:7], value.replace("Z", "+00:00"))
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _str_dict(value: object) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    return {str(k): str(v) for k, v in value.items()}


def parse_payload(payload: object, target_severities: frozenset[str]) -> Job | None:
    """firing 상태이고 대상 severity인 알람만 남긴다. 남는 게 없으면 None.

    resolved 알림은 분석하지 않는다 (해소 메시지는 Alertmanager가 Discord로 직접 보낸다).
    """
    if not isinstance(payload, dict) or not isinstance(payload.get("alerts"), list):
        raise PayloadError("alerts 목록이 없음")

    alerts = []
    for raw in payload["alerts"]:
        if not isinstance(raw, dict):
            continue
        alert = Alert(
            status=str(raw.get("status", "")),
            labels=_str_dict(raw.get("labels")),
            annotations=_str_dict(raw.get("annotations")),
            starts_at=_parse_time(raw.get("startsAt")),
            fingerprint=str(raw.get("fingerprint") or sorted(_str_dict(raw.get("labels")).items())),
        )
        if alert.status == "firing" and alert.severity in target_severities:
            alerts.append(alert)

    if not alerts:
        return None
    return Job(
        group_key=str(payload.get("groupKey") or alerts[0].name),
        alerts=tuple(alerts),
        common_labels=_str_dict(payload.get("commonLabels")),
        received_at=datetime.now(timezone.utc),
    )
