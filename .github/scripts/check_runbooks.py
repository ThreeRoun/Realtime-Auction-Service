"""알람 규칙과 Runbook(docs/runbooks)이 서로 맞는지 검사한다 (CI: lint.yml의 monitoring job).

- critical 알람은 runbook_url이 있어야 하고, 가리키는 파일이 docs/runbooks/<알람명>.md로 있어야 한다.
- Runbook 파일의 front matter(alert, severity)는 실제 알람 규칙과 같아야 한다.
- Runbook에는 트리아지 봇이 읽는 기본 절(의미와 영향 / 확인 / 조치 / 해소 확인)이 있어야 한다.
"""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
RULES_DIR = ROOT / "k8s/monitoring/config/rules"
RUNBOOK_DIR = ROOT / "docs/runbooks"
URL_PREFIX = "https://github.com/ThreeRoun/Realtime-Auction-Service/blob/baseline/docs/runbooks/"
REQUIRED_SECTIONS = ["## 의미와 영향", "## 확인", "## 조치", "## 해소 확인"]


def load_rules() -> dict[str, dict]:
    rules = {}
    for path in sorted(RULES_DIR.glob("*.rules.yml")):
        for group in yaml.safe_load(path.read_text(encoding="utf-8"))["groups"]:
            for rule in group["rules"]:
                if "alert" in rule:
                    rules[rule["alert"]] = rule
    return rules


def front_matter(text: str) -> dict:
    match = re.match(r"---\n(.*?)\n---\n", text, re.S)
    return yaml.safe_load(match.group(1)) if match else {}


def main() -> int:
    rules = load_rules()
    errors = []

    for name, rule in rules.items():
        severity = rule.get("labels", {}).get("severity")
        url = rule.get("annotations", {}).get("runbook_url")
        if severity == "critical" and not url:
            errors.append(f"{name}: critical 알람에 runbook_url이 없음")
        if url:
            if url != f"{URL_PREFIX}{name}.md":
                errors.append(f"{name}: runbook_url은 {URL_PREFIX}{name}.md 여야 함 (현재 {url})")
            if not (RUNBOOK_DIR / f"{name}.md").is_file():
                errors.append(f"{name}: docs/runbooks/{name}.md 파일이 없음")

    for path in sorted(RUNBOOK_DIR.glob("*.md")):
        if path.name == "README.md":
            continue
        text = path.read_text(encoding="utf-8")
        meta = front_matter(text)
        alert = path.stem
        if meta.get("alert") != alert:
            errors.append(f"{path.name}: front matter의 alert가 파일명({alert})과 다름")
        if alert not in rules:
            errors.append(f"{path.name}: 같은 이름의 알람 규칙이 없음")
        elif meta.get("severity") != rules[alert].get("labels", {}).get("severity"):
            errors.append(f"{path.name}: front matter의 severity가 알람 규칙과 다름")
        for section in REQUIRED_SECTIONS:
            if not re.search(rf"^{re.escape(section)}\s*$", text, re.M):
                errors.append(f"{path.name}: '{section}' 절이 없음")

    for error in errors:
        print(f"::error::{error}")
    if not errors:
        count = sum(1 for p in RUNBOOK_DIR.glob("*.md") if p.name != "README.md")
        print(f"runbooks OK ({count}개)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
