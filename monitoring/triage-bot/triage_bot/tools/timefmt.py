"""도구 결과에 찍는 시각은 모두 KST로 통일한다 (알람 메시지, Discord와 같은 기준)."""
from datetime import datetime, timezone

from ..alerts import KST


def kst(value: float | str | datetime | None, with_date: bool = True) -> str:
    """unix 초, RFC3339 문자열, datetime을 'MM-DD HH:MM:SS' (KST)로."""
    if value is None or value == "":
        return "?"
    if isinstance(value, (int, float)):
        moment = datetime.fromtimestamp(float(value), timezone.utc)
    elif isinstance(value, str):
        try:
            moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return value
    else:
        moment = value
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(KST).strftime("%m-%d %H:%M:%S" if with_date else "%H:%M:%S")


def ago(value: str | None, now: datetime) -> str:
    if not value:
        return "?"
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return "?"
    seconds = int((now - moment).total_seconds())
    if seconds < 120:
        return f"{seconds}초 전"
    if seconds < 7200:
        return f"{seconds // 60}분 전"
    if seconds < 172800:
        return f"{seconds // 3600}시간 전"
    return f"{seconds // 86400}일 전"
