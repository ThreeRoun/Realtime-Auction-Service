"""트리아지에 쓰는 읽기 전용 조회 도구 모음.

도구 이름은 Runbook의 [도구: ...] 표시와 같다. 각 도구는 JSON Schema(입력)와 실행 함수를 갖고,
다음 단계에서 Claude의 tool 정의로 그대로 넘긴다. 모든 결과는 마스킹하고 길이를 제한한다.
실패하면 예외 대신 "오류: ..." 문자열을 돌려준다 - Claude가 읽고 쿼리를 고쳐 다시 시도할 수 있게.
"""
from dataclasses import dataclass
from typing import Callable

from ..masking import scrub
from . import kubernetes, loki, prometheus, runbook
from .http import ToolError

OUTPUT_LIMIT = 6000


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    input_schema: dict
    run: Callable[..., str]

    def definition(self) -> dict:
        """Claude API의 tools 항목 형식"""
        return {"name": self.name, "description": self.description, "input_schema": self.input_schema}


def _schema(properties: dict, required: list[str]) -> dict:
    return {"type": "object", "properties": properties, "required": required, "additionalProperties": False}


_MINUTES = {"type": "integer", "minimum": 1, "maximum": 180,
            "description": "조회 기간(분, 지금부터 과거로). 알람 시작 전후가 들어가게 잡는다. 기본 30"}
_NAMESPACE = {"type": "string", "enum": list(kubernetes.NAMESPACES)}


class Toolbox:
    def __init__(self, prometheus_url: str, loki_url: str, runbook_dir: str,
                 kube: kubernetes.KubeClient | None = None):
        kube = kube or kubernetes.KubeClient()
        self.tools = {tool.name: tool for tool in [
            Tool(
                "query_prometheus",
                "PromQL 범위 조회. 시계열마다 최근값/최소/최대와 시간대별 추이를 돌려준다. "
                "에러율, 응답 시간, 커넥션 풀, JVM, 노드 자원, kube-state-metrics, cAdvisor 지표 확인용.",
                _schema({"promql": {"type": "string", "description": "PromQL 식"}, "minutes": _MINUTES},
                        ["promql"]),
                lambda promql, minutes=30: prometheus.query_range(prometheus_url, promql, minutes),
            ),
            Tool(
                "query_loki",
                'LogQL 조회 (최신순). 스트림 선택자에 namespace="auction" 또는 "monitoring" 조건이 필수다. '
                '라벨: namespace, service(앱 이름, 예: auction-engine, auction-db), pod, container. '
                '엔진 로그는 JSON이라 | json | level="ERROR" 로 거른다. count_over_time 같은 지표 쿼리도 된다.',
                _schema({"logql": {"type": "string"}, "minutes": _MINUTES,
                         "limit": {"type": "integer", "minimum": 1, "maximum": 100,
                                   "description": "최대 줄 수, 기본 50"}},
                        ["logql"]),
                lambda logql, minutes=30, limit=50: loki.query(loki_url, logql, minutes, limit),
            ),
            Tool(
                "k8s_status",
                "Kubernetes 상태 조회 (읽기 전용). kind=pods: phase, ready, 재시작 횟수, 대기 사유, 마지막 종료 사유. "
                "kind=events: 최근 이벤트(name으로 대상 이름 필터). kind=deployments/statefulsets: 목표/준비 replica, 이미지, 조건.",
                _schema({"namespace": _NAMESPACE, "kind": {"type": "string", "enum": list(kubernetes.KINDS)},
                         "selector": {"type": "string", "description": "pods용 라벨 셀렉터, 예: app=auction-engine"},
                         "name": {"type": "string", "description": "events용 대상 이름 부분 문자열, 예: auction-db"}},
                        ["namespace", "kind"]),
                lambda namespace, kind, selector="", name="": kubernetes.status(kube, namespace, kind, selector, name),
            ),
            Tool(
                "rollout_history",
                "Deployment의 배포 이력. ReplicaSet별 이미지 태그(커밋 SHA)와 생성 시각, 최신순. "
                "알람 직전에 배포가 있었는지 확인할 때 쓴다.",
                _schema({"namespace": _NAMESPACE, "deployment": {"type": "string", "description": "예: auction-engine"}},
                        ["namespace", "deployment"]),
                lambda namespace, deployment: kubernetes.rollout_history(kube, namespace, deployment),
            ),
            Tool(
                "get_runbook",
                "알람의 Runbook(확인 절차와 허용된 조치 목록)을 읽는다. 조치는 반드시 이 문서의 항목 중에서만 고른다.",
                _schema({"alertname": {"type": "string", "description": "예: AuctionEngineDown"}}, ["alertname"]),
                lambda alertname: runbook.get(runbook_dir, alertname),
            ),
        ]}

    def definitions(self) -> list[dict]:
        return [tool.definition() for tool in self.tools.values()]

    def run(self, name: str, arguments: dict) -> tuple[str, bool]:
        """(결과 텍스트, 오류 여부). 결과는 마스킹하고 OUTPUT_LIMIT자로 자른다."""
        tool = self.tools.get(name)
        if tool is None:
            return f"오류: 알 수 없는 도구 {name}", True
        allowed = set(tool.input_schema["properties"])
        unknown = set(arguments) - allowed
        missing = set(tool.input_schema["required"]) - set(arguments)
        if unknown or missing:
            return f"오류: 입력 확인 필요 (모르는 항목 {sorted(unknown)}, 빠진 항목 {sorted(missing)})", True
        try:
            text, is_error = tool.run(**arguments), False
        except ToolError as exc:
            text, is_error = f"오류: {exc}", True
        except (TypeError, ValueError) as exc:
            text, is_error = f"오류: 입력 형식 ({exc})", True
        text = scrub(text)
        if len(text) > OUTPUT_LIMIT:
            text = text[:OUTPUT_LIMIT] + f"\n…(결과가 길어 {OUTPUT_LIMIT}자에서 자름 - 조건을 좁혀서 다시 조회)"
        return text, is_error
