"""get_runbook: 이미지에 함께 들어 있는 docs/runbooks/<알람명>.md를 읽는다 (배포된 버전과 같은 내용)."""
import re
from pathlib import Path

from .http import ToolError

_ALERT = re.compile(r"^[A-Za-z0-9]+$")


def get(runbook_dir: str, alertname: str) -> str:
    if not _ALERT.match(alertname or ""):
        raise ToolError("alertname은 영문/숫자만 (예: AuctionEngineDown)")
    directory = Path(runbook_dir)
    path = directory / f"{alertname}.md"
    if not path.is_file():
        available = sorted(p.stem for p in directory.glob("*.md") if p.name != "README.md")
        raise ToolError(f"{alertname}의 Runbook이 없음. 있는 Runbook: {', '.join(available) or '없음'}")
    return path.read_text(encoding="utf-8")
