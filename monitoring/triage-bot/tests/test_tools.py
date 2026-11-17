import json
import sys
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from triage_bot import snapshot  # noqa: E402
from triage_bot.alerts import parse_payload  # noqa: E402
from triage_bot.analyzer import Analysis  # noqa: E402
from triage_bot.discord import build_message  # noqa: E402
from triage_bot.tools import OUTPUT_LIMIT, Toolbox, kubernetes, loki, prometheus, runbook  # noqa: E402
from triage_bot.tools.http import ToolError  # noqa: E402

RUNBOOK_DIR = str(Path(__file__).resolve().parents[3] / "docs/runbooks")
SHA = "6057199" + "a" * 33
NOW = datetime.now(timezone.utc)


def iso(minutes_ago: float) -> str:
    return (NOW - timedelta(minutes=minutes_ago)).isoformat().replace("+00:00", "Z")


def patch_get(response):
    """tools.http.get_json을 가짜로 바꾼다. response가 함수면 (url, params)로 호출해 결과를 쓴다."""
    def fake(url, params=None, headers=None, context=None, timeout=10.0):
        return response(url, params) if callable(response) else response
    return mock.patch("triage_bot.tools.http.get_json", side_effect=fake)


class FakeKube:
    def __init__(self, responses: dict):
        self.responses = responses
        self.calls = []

    def get(self, path, params=None):
        self.calls.append((path, params))
        return self.responses[path]


class PrometheusTest(unittest.TestCase):
    def test_summarizes_each_series(self):
        now = time.time()
        body = {"status": "success", "data": {"resultType": "matrix", "result": [
            {"metric": {"__name__": "up", "job": "auction-engine"},
             "values": [[now - 60 * i, str(v)] for i, v in zip(range(30, 0, -1), [1] * 25 + [0] * 5)]},
        ]}}
        with patch_get(body):
            text = prometheus.query_range("http://prom", 'up{job="auction-engine"}', 30)
        self.assertIn('up{job="auction-engine"}', text)
        self.assertIn("최근=0", text)
        self.assertIn("최소=0, 최대=1", text)
        self.assertIn("추이:", text)

    def test_empty_result_has_hint(self):
        with patch_get({"status": "success", "data": {"result": []}}):
            self.assertIn("결과 없음", prometheus.query_range("http://prom", "nope", 10))

    def test_series_cap(self):
        series = [{"metric": {"pod": f"p{i}"}, "values": [[time.time(), "1"]]} for i in range(20)]
        with patch_get({"status": "success", "data": {"result": series}}):
            text = prometheus.query_range("http://prom", "x", 10)
        self.assertIn("시계열 5개 생략", text)

    def test_validation(self):
        with self.assertRaises(ToolError):
            prometheus.query_range("http://prom", "  ", 10)
        with self.assertRaises(ToolError):
            prometheus.query_range("http://prom", "x" * 3000, 10)

    def test_minutes_are_clamped(self):
        captured = {}

        def respond(url, params):
            captured.update(params)
            return {"status": "success", "data": {"result": []}}
        with patch_get(respond):
            prometheus.query_range("http://prom", "up", 100000)
        self.assertEqual(float(captured["end"]) - float(captured["start"]), 180 * 60)


class LokiTest(unittest.TestCase):
    def stream(self, *lines, pod="auction-engine-abc"):
        base = time.time_ns()
        return {"status": "success", "data": {"resultType": "streams", "result": [
            {"stream": {"pod": pod, "service": "auction-engine"},
             "values": [[str(base - i * 10**9), line] for i, line in enumerate(lines)]}]}}

    def test_requires_namespace(self):
        with self.assertRaisesRegex(ToolError, "namespace"):
            loki.query("http://loki", '{service="auction-engine"}')
        with self.assertRaisesRegex(ToolError, "namespace"):
            loki.query("http://loki", '{namespace="kube-system"}')

    def test_json_logs_are_compacted(self):
        line = json.dumps({"level": "ERROR", "message": "Request processing failed",
                           "logger_name": "x", "stack_trace": "java.lang.NullPointerException: boom\n\tat a.b(C.java:1)"})
        with patch_get(self.stream(line, "plain text line")):
            text = loki.query("http://loki", '{namespace="auction", service="auction-engine"} | json | level="ERROR"')
        self.assertIn("[auction-engine-abc] ERROR Request processing failed | java.lang.NullPointerException: boom", text)
        self.assertIn("plain text line", text)
        self.assertNotIn("at a.b", text)
        # 최신순
        self.assertLess(text.index("Request processing"), text.index("plain text line"))

    def test_relay_logs_keep_event_and_err(self):
        lines = [
            json.dumps({"level": "ERROR", "event": "redis_error", "err": "getaddrinfo ENOTFOUND auction-redis",
                        "message": "Redis 연결 오류"}),
            json.dumps({"level": "FATAL", "event": "server_error", "reason": "port_in_use", "port": 8081,
                        "message": "중계 서버 오류"}),
            json.dumps({"level": "ERROR", "event": "socket_error", "message": "소켓 에러",
                        "err": {"type": "Error", "message": "read ECONNRESET",
                                "stack": "Error: read ECONNRESET\n    at TCP.onStreamRead"}}),
        ]
        with patch_get(self.stream(*lines)):
            text = loki.query("http://loki", '{namespace="auction", service="realtime-relay"} | json')
        self.assertIn("ERROR [redis_error] Redis 연결 오류 | getaddrinfo ENOTFOUND auction-redis", text)
        self.assertIn("FATAL [server_error] 중계 서버 오류 | reason=port_in_use", text)
        self.assertIn("ERROR [socket_error] 소켓 에러 | Error: read ECONNRESET", text)
        self.assertNotIn("onStreamRead", text)

    def test_root_cause_is_kept(self):
        stack = ("org.x.BeanCreationException: Error creating bean\n\tat a.b(C.java:1)\n"
                 "Caused by: org.postgresql.util.PSQLException: The connection attempt failed.\n\tat d.e(F.java:2)\n"
                 "Caused by: java.net.UnknownHostException: wrong-db\n\tat g.h(I.java:3)")
        line = json.dumps({"level": "ERROR", "message": "Application run failed", "stack_trace": stack})
        with patch_get(self.stream(line)):
            text = loki.query("http://loki", '{namespace="auction"} | json')
        self.assertIn("ERROR Application run failed | org.x.BeanCreationException: Error creating bean"
                      " | Caused by: java.net.UnknownHostException: wrong-db", text)
        self.assertNotIn("PSQLException", text)

    def test_metric_query(self):
        body = {"status": "success", "data": {"resultType": "matrix", "result": [
            {"metric": {"service": "auction-engine"}, "values": [[time.time(), "3"], [time.time(), "12"]]}]}}
        with patch_get(body):
            text = loki.query("http://loki", 'sum by (service) (count_over_time({namespace="auction"}[5m]))')
        self.assertIn('{service="auction-engine"}: 최근=12, 최대=12', text)

    def test_limit_reached_hint(self):
        with patch_get(self.stream(*[f"line {i}" for i in range(5)])):
            text = loki.query("http://loki", '{namespace="auction"}', limit=5)
        self.assertIn("상한 도달", text)


class KubernetesTest(unittest.TestCase):
    def pod(self, name, phase="Running", ready=True, restarts=0, waiting=None, terminated=None):
        status = {"name": "auction-engine", "ready": ready, "restartCount": restarts, "state": {}, "lastState": {}}
        if waiting:
            status["state"]["waiting"] = waiting
        if terminated:
            status["lastState"]["terminated"] = terminated
        return {"metadata": {"name": name, "creationTimestamp": iso(30)},
                "spec": {"containers": [{"name": "auction-engine",
                                         "image": f"ghcr.io/threeroun/auction-engine:{SHA}"}]},
                "status": {"phase": phase, "containerStatuses": [status]}}

    def test_pods_summary(self):
        kube = FakeKube({"/api/v1/namespaces/auction/pods": {"items": [
            self.pod("auction-engine-1", ready=False, restarts=4,
                     waiting={"reason": "CrashLoopBackOff", "message": "back-off 2m40s"},
                     terminated={"reason": "OOMKilled", "exitCode": 137, "finishedAt": iso(2)}),
        ]}})
        text = kubernetes.status(kube, "auction", "pods", "app=auction-engine")
        self.assertIn("auction-engine-1 phase=Running ready=0/1 restarts=4 생성=30분 전 image=auction-engine:6057199", text)
        self.assertIn("대기=CrashLoopBackOff(back-off 2m40s)", text)
        self.assertIn("마지막종료=OOMKilled exit=137", text)
        self.assertEqual(kube.calls[0][1], {"labelSelector": "app=auction-engine"})

    def test_events_filtered_and_newest_first(self):
        def event(name, reason, minutes_ago):
            return {"type": "Warning", "reason": reason, "message": f"{reason} msg", "count": 3,
                    "lastTimestamp": iso(minutes_ago), "involvedObject": {"kind": "Pod", "name": name}}
        kube = FakeKube({"/api/v1/namespaces/auction/events": {"items": [
            event("auction-db-0", "Unhealthy", 10), event("auction-db-0", "BackOff", 1),
            event("frontend-1", "Failed", 0)]}})
        text = kubernetes.status(kube, "auction", "events", name="auction-db")
        self.assertNotIn("frontend", text)
        self.assertIn("2개", text)
        self.assertLess(text.index("BackOff"), text.index("Unhealthy"))
        self.assertIn("pod/auction-db-0 (x3)", text)

    def test_deployment_conditions(self):
        kube = FakeKube({"/apis/apps/v1/namespaces/auction/deployments": {"items": [{
            "metadata": {"name": "auction-engine"}, "spec": {"replicas": 1, "template": {"spec": {"containers": [
                {"image": f"ghcr.io/threeroun/auction-engine:{SHA}"}]}}},
            "status": {"readyReplicas": 0, "updatedReplicas": 1, "conditions": [
                {"type": "Available", "status": "False", "reason": "MinimumReplicasUnavailable", "message": "no"},
                {"type": "Progressing", "status": "True", "reason": "NewReplicaSetAvailable"}]}}]}})
        text = kubernetes.status(kube, "auction", "deployments")
        self.assertIn("auction-engine 목표=1 ready=0 updated=1 available=0", text)
        self.assertIn("Available=False (MinimumReplicasUnavailable)", text)
        self.assertNotIn("Progressing", text)

    def test_rollout_history(self):
        def rs(revision, sha, minutes_ago, owner="auction-engine"):
            return {"metadata": {"name": f"rs-{revision}", "creationTimestamp": iso(minutes_ago),
                                 "annotations": {"deployment.kubernetes.io/revision": str(revision)},
                                 "ownerReferences": [{"kind": "Deployment", "name": owner}]},
                    "spec": {"replicas": 1 if revision == 3 else 0,
                             "template": {"spec": {"containers": [{"image": f"ghcr.io/threeroun/auction-engine:{sha}"}]}}},
                    "status": {"readyReplicas": 1 if revision == 3 else 0}}
        kube = FakeKube({"/apis/apps/v1/namespaces/auction/replicasets": {"items": [
            rs(2, "b" * 40, 600), rs(3, SHA, 12), rs(1, "c" * 40, 3000), rs(9, "d" * 40, 1, owner="frontend")]}})
        lines = kubernetes.rollout_history(kube, "auction", "auction-engine").splitlines()
        self.assertIn("rev 3 (현재)", lines[1])
        self.assertIn("(12분 전) image=auction-engine:6057199 replicas=1/1", lines[1])
        self.assertIn("rev 2", lines[2])
        self.assertEqual(len(lines), 4)   # frontend 소유 ReplicaSet 제외

    def test_guards(self):
        kube = FakeKube({})
        with self.assertRaisesRegex(ToolError, "namespace"):
            kubernetes.status(kube, "kube-system", "pods")
        with self.assertRaisesRegex(ToolError, "kind"):
            kubernetes.status(kube, "auction", "secrets")
        with self.assertRaisesRegex(ToolError, "selector"):
            kubernetes.status(kube, "auction", "pods", "app in (a b)")
        with self.assertRaisesRegex(ToolError, "deployment"):
            kubernetes.rollout_history(kube, "auction", "../secrets")
        self.assertEqual(kube.calls, [])

    def test_outside_cluster(self):
        with mock.patch.dict("os.environ", {}, clear=True):
            with self.assertRaisesRegex(ToolError, "클러스터 밖"):
                kubernetes.KubeClient().get("/api/v1/namespaces/auction/pods")


class RunbookTest(unittest.TestCase):
    def test_reads_repo_runbook(self):
        self.assertIn("alert: AuctionEngineDown", runbook.get(RUNBOOK_DIR, "AuctionEngineDown"))

    def test_missing_and_invalid(self):
        with self.assertRaisesRegex(ToolError, "있는 Runbook: .*NodeDiskSpaceCritical"):
            runbook.get(RUNBOOK_DIR, "NodeCpuHigh")
        with self.assertRaisesRegex(ToolError, "영문"):
            runbook.get(RUNBOOK_DIR, "../README")


class ToolboxTest(unittest.TestCase):
    def setUp(self):
        self.toolbox = Toolbox("http://prom", "http://loki", RUNBOOK_DIR, kube=FakeKube({}))

    def test_definitions_match_runbook_tool_names(self):
        names = {d["name"] for d in self.toolbox.definitions()}
        self.assertEqual(names, {"query_prometheus", "query_loki", "k8s_status", "rollout_history", "get_runbook"})
        documented = set()
        for path in Path(RUNBOOK_DIR).glob("*.md"):
            documented |= set(__import__("re").findall(r"\[도구: ([a-z_]+)\]", path.read_text(encoding="utf-8")))
        self.assertTrue(documented <= names, f"Runbook에만 있는 도구: {documented - names}")

    def test_output_is_masked(self):
        secret = "eyJ" + "a" * 12 + "." + "b" * 12 + "." + "c" * 12
        body = {"status": "success", "data": {"resultType": "streams", "result": [
            {"stream": {"pod": "p"}, "values": [[str(time.time_ns()), f"login user@example.com token {secret}"]]}]}}
        with patch_get(body):
            text, is_error = self.toolbox.run("query_loki", {"logql": '{namespace="auction"}'})
        self.assertFalse(is_error)
        self.assertNotIn("user@example.com", text)
        self.assertNotIn(secret, text)

    def test_long_output_is_truncated(self):
        body = {"status": "success", "data": {"resultType": "streams", "result": [
            {"stream": {"pod": "p"}, "values": [[str(time.time_ns() - i), "x" * 299] for i in range(100)]}]}}
        with patch_get(body):
            text, _ = self.toolbox.run("query_loki", {"logql": '{namespace="auction"}', "limit": 100})
        self.assertLessEqual(len(text), OUTPUT_LIMIT + 100)
        self.assertIn("자름", text)

    def test_errors_are_returned_as_text(self):
        self.assertEqual(self.toolbox.run("delete_pod", {})[1], True)
        text, is_error = self.toolbox.run("k8s_status", {"namespace": "auction"})
        self.assertTrue(is_error)
        self.assertIn("빠진 항목 ['kind']", text)
        text, is_error = self.toolbox.run("k8s_status", {"namespace": "auction", "kind": "pods", "verb": "delete"})
        self.assertIn("모르는 항목 ['verb']", text)
        text, is_error = self.toolbox.run("query_loki", {"logql": "{}"})
        self.assertTrue(text.startswith("오류: "))


class SnapshotTest(unittest.TestCase):
    def job(self, **labels):
        alert = {"status": "firing", "fingerprint": "f", "startsAt": iso(1), "annotations": {},
                 "labels": {"alertname": "X", "severity": "critical", **labels}}
        return parse_payload({"groupKey": "g", "alerts": [alert]}, frozenset({"critical"}))

    def test_plans(self):
        self.assertEqual([t for t, _, _ in snapshot.plan(self.job(service="auction-engine"))],
                         ["엔진 Pod", "엔진 배포 이력"])
        title, tool, args = snapshot.plan(self.job(service="kubernetes", namespace="auction",
                                                   statefulset="auction-db"))[0]
        self.assertEqual((tool, args["selector"]), ("k8s_status", "app=auction-db"))
        self.assertEqual(snapshot.plan(self.job(service="node"))[0][1], "query_prometheus")
        # 허용되지 않은 네임스페이스의 StatefulSet은 조회하지 않음
        self.assertEqual(snapshot.plan(self.job(service="kubernetes", namespace="kube-system", statefulset="x")), [])

    def test_message_includes_snapshot_and_closes_code_block(self):
        job = self.job(service="auction-engine")
        analysis = Analysis("pending", "요약", ["a"], {}, [("엔진 Pod", "x" * 5000)])
        description = build_message(job, analysis)["embeds"][0]["description"]
        self.assertLessEqual(len(description), 4000)
        self.assertEqual(description.count("```") % 2, 0)


if __name__ == "__main__":
    unittest.main()
