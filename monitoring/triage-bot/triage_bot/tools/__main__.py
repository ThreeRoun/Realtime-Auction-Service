"""도구를 직접 실행해 보는 CLI (봇 Pod 안에서 같은 권한으로 확인할 때).

    kubectl -n monitoring exec deploy/triage-bot -- python -m triage_bot.tools list
    kubectl -n monitoring exec deploy/triage-bot -- python -m triage_bot.tools k8s_status '{"namespace":"auction","kind":"pods"}'
"""
import json
import sys

from ..config import Config
from . import Toolbox


def main(argv: list[str]) -> int:
    config = Config.from_env()
    toolbox = Toolbox(config.prometheus_url, config.loki_url, config.runbook_dir)
    if len(argv) < 1 or argv[0] == "list":
        for definition in toolbox.definitions():
            print(f"{definition['name']}: {json.dumps(definition['input_schema']['properties'], ensure_ascii=False)}")
        return 0
    arguments = json.loads(argv[1]) if len(argv) > 1 else {}
    text, is_error = toolbox.run(argv[0], arguments)
    print(text)
    return 1 if is_error else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
