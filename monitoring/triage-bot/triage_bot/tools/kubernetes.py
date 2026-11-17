"""k8s_status / rollout_history: Kubernetes API를 읽기 전용으로 조회해 요약한다.

Pod의 ServiceAccount 토큰으로 API 서버에 접속한다. 권한은 RBAC Role로 auction / monitoring 네임스페이스의
pods, events, deployments, replicasets, statefulsets 조회(get/list)만 있다 (k8s/monitoring/triage-bot.yaml).
logs, exec, secrets 권한은 없다 - 로그는 Loki로 본다.
"""
import os
import re
import ssl
from datetime import datetime, timezone
from pathlib import Path

from . import http
from .http import ToolError
from .timefmt import ago, kst

SA_DIR = Path("/var/run/secrets/kubernetes.io/serviceaccount")
NAMESPACES = ("auction", "monitoring")
KINDS = ("pods", "events", "deployments", "statefulsets")
_SELECTOR = re.compile(r"^[A-Za-z0-9._/=,!-]*$")
_NAME = re.compile(r"^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$")
MAX_ITEMS = 30
MESSAGE_LIMIT = 200


class KubeClient:
    def __init__(self, sa_dir: Path = SA_DIR):
        host = os.environ.get("KUBERNETES_SERVICE_HOST")
        port = os.environ.get("KUBERNETES_SERVICE_PORT", "443")
        self.base = f"https://{host}:{port}" if host else ""
        self.sa_dir = sa_dir

    def get(self, path: str, params: dict | None = None) -> dict:
        if not self.base:
            raise ToolError("클러스터 밖에서 실행 중이라 Kubernetes API를 쓸 수 없음")
        try:
            token = (self.sa_dir / "token").read_text().strip()
        except OSError:
            raise ToolError("ServiceAccount 토큰이 없음 (automountServiceAccountToken 확인)") from None
        context = ssl.create_default_context(cafile=str(self.sa_dir / "ca.crt"))
        return http.get_json(self.base + path, params, {"Authorization": f"Bearer {token}"}, context)


def _check_namespace(namespace: str) -> str:
    if namespace not in NAMESPACES:
        raise ToolError(f"namespace는 {', '.join(NAMESPACES)} 중 하나여야 함")
    return namespace


def _image_tag(image: str) -> str:
    """ghcr.io/threeroun/auction-engine:<40자리 SHA> → auction-engine:<SHA 7자리>"""
    name, _, tag = image.rsplit("/", 1)[-1].partition(":")
    if re.fullmatch(r"[0-9a-f]{40}", tag):
        tag = tag[:7]
    return f"{name}:{tag}" if tag else name


def _container_lines(pod: dict) -> list[str]:
    lines = []
    for status in pod.get("status", {}).get("containerStatuses", []) or []:
        parts = []
        waiting = (status.get("state") or {}).get("waiting")
        if waiting:
            reason = waiting.get("reason", "")
            message = (waiting.get("message") or "")[:MESSAGE_LIMIT]
            parts.append(f"대기={reason}" + (f"({message})" if message else ""))
        terminated = (status.get("lastState") or {}).get("terminated")
        if terminated:
            parts.append(f"마지막종료={terminated.get('reason', '?')} exit={terminated.get('exitCode', '?')} "
                         f"at {kst(terminated.get('finishedAt'))}")
        if parts:
            lines.append(f"    {status.get('name')}: " + ", ".join(parts))
    return lines


def _pods(items: list, now: datetime) -> list[str]:
    lines = []
    for pod in items:
        meta, status, spec = pod.get("metadata", {}), pod.get("status", {}), pod.get("spec", {})
        containers = status.get("containerStatuses", []) or []
        ready = sum(1 for c in containers if c.get("ready"))
        restarts = sum(c.get("restartCount", 0) for c in containers)
        phase = status.get("phase", "?")
        if meta.get("deletionTimestamp"):
            phase += "(종료 중)"
        images = ", ".join(_image_tag(c.get("image", "")) for c in spec.get("containers", []))
        lines.append(f"- {meta.get('name')} phase={phase} ready={ready}/{len(spec.get('containers', []))} "
                     f"restarts={restarts} 생성={ago(meta.get('creationTimestamp'), now)} image={images}")
        lines += _container_lines(pod)
        for cond in status.get("conditions", []) or []:
            if cond.get("type") == "PodScheduled" and cond.get("status") != "True":
                lines.append(f"    스케줄 실패: {(cond.get('message') or '')[:MESSAGE_LIMIT]}")
    return lines


def _events(items: list) -> list[str]:
    def when(event):
        return (event.get("lastTimestamp") or event.get("eventTime")
                or event.get("metadata", {}).get("creationTimestamp") or "")
    items = sorted(items, key=when, reverse=True)
    lines = []
    for event in items[:MAX_ITEMS]:
        obj = event.get("involvedObject", {})
        count = event.get("count") or 1
        lines.append(f"- {kst(when(event))} {event.get('type')} {event.get('reason')} "
                     f"{obj.get('kind', '').lower()}/{obj.get('name')}{f' (x{count})' if count > 1 else ''}: "
                     f"{(event.get('message') or '')[:MESSAGE_LIMIT]}")
    return lines


def _workloads(items: list, kind: str) -> list[str]:
    lines = []
    for item in items:
        meta, spec, status = item.get("metadata", {}), item.get("spec", {}), item.get("status", {})
        images = ", ".join(_image_tag(c.get("image", "")) for c in spec.get("template", {}).get("spec", {}).get("containers", []))
        line = (f"- {meta.get('name')} 목표={spec.get('replicas', '?')} ready={status.get('readyReplicas', 0)} "
                f"updated={status.get('updatedReplicas', 0)}")
        if kind == "deployments":
            line += f" available={status.get('availableReplicas', 0)}"
        lines.append(line + f" image={images}")
        for cond in status.get("conditions", []) or []:
            if cond.get("status") != "True" or cond.get("type") == "ReplicaFailure":
                lines.append(f"    {cond.get('type')}={cond.get('status')} ({cond.get('reason', '')}): "
                             f"{(cond.get('message') or '')[:MESSAGE_LIMIT]}")
    return lines


def status(client: KubeClient, namespace: str, kind: str, selector: str = "", name: str = "") -> str:
    """kind: pods(selector로 거름) / events(name이 들어간 대상만) / deployments / statefulsets"""
    namespace = _check_namespace(namespace)
    if kind not in KINDS:
        raise ToolError(f"kind는 {', '.join(KINDS)} 중 하나여야 함")
    if not _SELECTOR.match(selector or ""):
        raise ToolError("selector 형식이 올바르지 않음 (예: app=auction-engine)")
    params = {"labelSelector": selector} if selector and kind != "events" else None
    group = "/api/v1" if kind in ("pods", "events") else "/apis/apps/v1"
    items = client.get(f"{group}/namespaces/{namespace}/{kind}", params).get("items", [])
    now = datetime.now(timezone.utc)

    if kind == "events":
        items = [e for e in items if (name or "") in e.get("involvedObject", {}).get("name", "")]
        lines = _events(items)
    elif kind == "pods":
        lines = _pods(items[:MAX_ITEMS], now)
    else:
        lines = _workloads(items[:MAX_ITEMS], kind)
    header = f"{namespace} {kind}"
    if selector and kind != "events":
        header += f" (selector {selector})"
    if name and kind == "events":
        header += f" (대상 이름에 '{name}' 포함)"
    header += f": {len(items)}개" + (f" (최근 {MAX_ITEMS}개만 표시)" if len(items) > MAX_ITEMS else "")
    return header + ("\n" + "\n".join(lines) if lines else "\n(없음)")


def rollout_history(client: KubeClient, namespace: str, deployment: str) -> str:
    """Deployment의 ReplicaSet(=배포 버전)별 이미지 태그(커밋 SHA)와 생성 시각, 최신순."""
    namespace = _check_namespace(namespace)
    if not _NAME.match(deployment or ""):
        raise ToolError("deployment 이름 형식이 올바르지 않음")
    replica_sets = client.get(f"/apis/apps/v1/namespaces/{namespace}/replicasets").get("items", [])
    owned = [rs for rs in replica_sets
             if any(ref.get("kind") == "Deployment" and ref.get("name") == deployment
                    for ref in rs.get("metadata", {}).get("ownerReferences", []) or [])]
    if not owned:
        return f"{namespace}/{deployment}: ReplicaSet 없음 (Deployment 이름 확인)"

    def revision(rs):
        return int(rs["metadata"].get("annotations", {}).get("deployment.kubernetes.io/revision", 0))
    owned.sort(key=revision, reverse=True)
    now = datetime.now(timezone.utc)
    lines = [f"{namespace}/{deployment} 배포 이력 (최신순, 이미지 태그 = 커밋 SHA)"]
    for index, rs in enumerate(owned[:10]):
        meta, spec, status = rs["metadata"], rs.get("spec", {}), rs.get("status", {})
        images = ", ".join(_image_tag(c.get("image", "")) for c in spec.get("template", {}).get("spec", {}).get("containers", []))
        marker = " (현재)" if index == 0 else ""
        lines.append(f"- rev {revision(rs)}{marker}: 생성 {kst(meta.get('creationTimestamp'))} "
                     f"({ago(meta.get('creationTimestamp'), now)}) image={images} "
                     f"replicas={status.get('readyReplicas', 0)}/{spec.get('replicas', 0)}")
    return "\n".join(lines)
