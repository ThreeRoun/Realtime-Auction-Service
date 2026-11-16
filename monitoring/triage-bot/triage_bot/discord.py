"""Discord webhook으로 분석 결과를 게시한다.

- webhook URL은 매번 파일에서 읽는다 (Secret을 바꾸면 재시작 없이 반영).
- 멘션(@everyone 등)은 allowed_mentions로 막는다 - 알람/로그 내용이 메시지에 섞이기 때문.
- 429(rate limit)와 5xx는 몇 번 재시도한다.
"""
import json
import logging
import time
import urllib.error
import urllib.request
from pathlib import Path

from .alerts import Job
from .analyzer import Analysis

log = logging.getLogger(__name__)

DESCRIPTION_LIMIT = 4000     # Discord embed description 최대 4096자
COLOR = {"pending": 0x5865F2, "ok": 0x5865F2, "failed": 0x99AAB5}
USER_AGENT = "triage-bot (Realtime-Auction-Service)"


class DiscordError(RuntimeError):
    pass


def build_message(job: Job, analysis: Analysis) -> dict:
    lines = [analysis.summary, ""]
    lines += [f"• {d}" for d in analysis.details]
    if analysis.runbooks:
        lines.append("")
        lines += [f"📘 [{name} Runbook]({url})" for name, url in sorted(analysis.runbooks.items())]
    for title, result in analysis.snapshot:
        # 결과 안에 ``` 가 있으면 코드 블록이 깨지므로 바꾼다
        lines += ["", f"**{title}** (자동 조회)", "```", result.replace("```", "'''"), "```"]
    description = "\n".join(lines)
    if len(description) > DESCRIPTION_LIMIT:
        description = description[:DESCRIPTION_LIMIT - 6]
        if description.count("```") % 2:      # 잘린 코드 블록은 닫아 준다
            description += "\n```"
        description += "\n…"
    return {
        "username": "triage-bot",
        "allowed_mentions": {"parse": []},
        "embeds": [{
            "title": f"🔎 트리아지: {job.title}",
            "description": description,
            "color": COLOR.get(analysis.status, COLOR["pending"]),
            "footer": {"text": f"triage-bot · job {job.job_id}"},
        }],
    }


class DiscordClient:
    def __init__(self, webhook_url_file: str, max_attempts: int = 3, timeout: float = 10.0,
                 sleep=time.sleep):
        self._url_file = Path(webhook_url_file)
        self._max_attempts = max_attempts
        self._timeout = timeout
        self._sleep = sleep

    def _url(self) -> str:
        try:
            url = self._url_file.read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise DiscordError(f"webhook URL 파일을 읽을 수 없음: {self._url_file}") from exc
        if not url.startswith(("https://", "http://")):
            raise DiscordError("webhook URL 형식이 올바르지 않음")
        return url

    def post(self, message: dict) -> None:
        url = self._url()
        body = json.dumps(message, ensure_ascii=False).encode("utf-8")
        for attempt in range(1, self._max_attempts + 1):
            request = urllib.request.Request(url, data=body, method="POST", headers={
                "Content-Type": "application/json", "User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(request, timeout=self._timeout):
                    return
            except urllib.error.HTTPError as exc:
                retryable = exc.code == 429 or exc.code >= 500
                if not retryable or attempt == self._max_attempts:
                    raise DiscordError(f"Discord 응답 {exc.code}") from None
                self._sleep(min(_retry_after(exc), 10.0))
            except (urllib.error.URLError, TimeoutError) as exc:
                if attempt == self._max_attempts:
                    raise DiscordError(f"Discord 연결 실패: {type(exc).__name__}") from None
                self._sleep(2.0 * attempt)


def _retry_after(exc: urllib.error.HTTPError) -> float:
    try:
        return float(json.loads(exc.read() or b"{}").get("retry_after", 1.0))
    except (ValueError, AttributeError):
        return 1.0
