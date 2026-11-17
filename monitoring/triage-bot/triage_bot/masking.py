"""조회 결과(로그, 이벤트 메시지 등)를 Claude나 Discord로 내보내기 전에 비밀값을 가린다.

패턴은 monitoring/ai-monitor의 scrub()과 같은 기준이다. 다만 UUID(상품/사용자 ID)는 줄이지 않는다 -
분석할 때 같은 상품의 요청끼리 묶어 봐야 하고, 결과는 팀 내부 Discord 채널에만 게시되기 때문이다.
"""
import os
import re

_SENSITIVE_ENV_NAMES = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")
_PATTERNS = [
    (re.compile(r"sk-ant-[A-Za-z0-9_\-]{6,}"), "[REDACTED_API_KEY]"),
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"), "[REDACTED_GH_TOKEN]"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{20,}"), "[REDACTED_GH_TOKEN]"),
    (re.compile(r"eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]*"), "[REDACTED_JWT]"),
    (re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-+/=]{8,}"), "Bearer [REDACTED]"),
    (re.compile(r"https?://(?:discord|discordapp)\.com/api/webhooks/\S+"), "[REDACTED_WEBHOOK]"),
    (re.compile(r"(?i)(password|passwd|secret|api[_-]?key|authorization)(\"?\s*[:=]\s*\"?)[^\s\",}]+"),
     r"\1\2[REDACTED]"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"), "[REDACTED_EMAIL]"),
]


def scrub(text: str) -> str:
    if not text:
        return text
    for name in _SENSITIVE_ENV_NAMES:
        value = os.environ.get(name)
        if value and len(value) >= 8:
            text = text.replace(value, "[REDACTED]")
    for pattern, replacement in _PATTERNS:
        text = pattern.sub(replacement, text)
    return text
