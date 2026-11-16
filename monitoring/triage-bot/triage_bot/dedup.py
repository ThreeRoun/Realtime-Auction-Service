"""같은 알람을 반복 분석하지 않도록 거르는 규칙.

Alertmanager는 해소되지 않은 critical 알람을 1시간마다, 그룹에 알람이 추가되면 5분 뒤 다시 보낸다.
- 쿨다운: 같은 그룹(groupKey)의 같은 알람들(fingerprint)은 cooldown 동안 다시 분석하지 않는다.
  그룹에 새 알람이 추가된 경우(fingerprint가 늘어남)는 상황이 바뀐 것이므로 다시 분석한다.
- 일일 상한: KST 기준 하루 분석 횟수를 제한한다 (분석 비용 상한).
상태는 메모리에만 둔다 - 봇이 재시작되면 초기화되지만, 그 경우 한 번 더 분석하는 정도라 괜찮다.
"""
import threading
from datetime import datetime, timedelta

from .alerts import KST, Job

ACCEPT = "accepted"
SKIP_COOLDOWN = "skipped_cooldown"
SKIP_DAILY_LIMIT = "skipped_daily_limit"


class Gate:
    def __init__(self, cooldown: timedelta, daily_limit: int):
        self._cooldown = cooldown
        self._daily_limit = daily_limit
        self._seen: dict[str, tuple[frozenset[str], datetime]] = {}
        self._day = None
        self._count = 0
        self._lock = threading.Lock()

    def check(self, job: Job, now: datetime) -> str:
        """분석할지 결정하고, 분석한다면 상태에 기록한다."""
        with self._lock:
            self._expire(now)
            previous = self._seen.get(job.group_key)
            if previous and job.fingerprints <= previous[0]:
                return SKIP_COOLDOWN

            today = now.astimezone(KST).date()
            if today != self._day:
                self._day, self._count = today, 0
            if self._count >= self._daily_limit:
                return SKIP_DAILY_LIMIT

            self._count += 1
            known = previous[0] if previous else frozenset()
            self._seen[job.group_key] = (known | job.fingerprints, now)
            return ACCEPT

    def _expire(self, now: datetime) -> None:
        for key, (_, handled_at) in list(self._seen.items()):
            if now - handled_at >= self._cooldown:
                del self._seen[key]
