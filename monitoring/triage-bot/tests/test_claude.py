"""Claude 분석 루프 테스트. 실제 API 대신 가짜 클라이언트로 응답 순서를 정해 둔다."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from test_triage_bot import alert, payload  # noqa: E402
from triage_bot.alerts import parse_payload  # noqa: E402
from triage_bot.analyzer import analyze  # noqa: E402
from triage_bot.claude import BETAS, ClaudeAnalyst, ClaudeError  # noqa: E402
from triage_bot.discord import build_message  # noqa: E402
from triage_bot.metrics import Metrics  # noqa: E402

FINDING = {
    "summary": "엔진이 DB 주소를 찾지 못해 기동에 실패하고 있다",
    "likely_cause": "직전 배포(rev 3)에서 SPRING_DATASOURCE_URL이 잘못 바뀜",
    "confidence": "high",
    "evidence": ["Pod CrashLoopBackOff, 재시작 3회", "로그: UnknownHostException: wrong-db"],
    "actions": ["A1: 직전 revision으로 롤백 (조건 충족: 알람 직전 배포)"],
    "human_checks": ["배포 변경 내용 확인"],
}


def text_block(text):
    return SimpleNamespace(type="text", text=text)


def tool_use(name, arguments, block_id="tu1"):
    return SimpleNamespace(type="tool_use", id=block_id, name=name, input=arguments)


def response(stop_reason, *content, model="claude-opus-5-5", **extra):
    usage = SimpleNamespace(input_tokens=100, output_tokens=50,
                            cache_creation_input_tokens=1000, cache_read_input_tokens=2000)
    return SimpleNamespace(stop_reason=stop_reason, content=list(content), model=model, usage=usage, **extra)


def final(finding=FINDING, **extra):
    return response("end_turn", SimpleNamespace(type="thinking", thinking=""), text_block(json.dumps(finding)), **extra)


class FakeClient:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self.create))

    def create(self, **params):
        # messages 리스트는 이후에 계속 바뀌므로 호출 시점의 길이를 같이 남긴다
        self.requests.append({**params, "message_count": len(params["messages"])})
        item = self.responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class FakeToolbox:
    def __init__(self):
        self.calls = []

    def definitions(self):
        return [{"name": "k8s_status", "description": "x", "input_schema": {"type": "object", "properties": {}}}]

    def run(self, name, arguments):
        self.calls.append((name, arguments))
        if name == "get_runbook":
            return f"# {arguments['alertname']} Runbook\n## 조치\n### A1. 롤백", False
        return f"{name} 결과 token=sk-ant-abcdefghijklmnopqrstuvwxyz", False


def job():
    return parse_payload(payload(alert(runbook_url="https://example.com/rb")), frozenset({"critical"}))


class ClaudeTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.key_file = Path(self.tmp.name) / "api-key"
        self.key_file.write_text("sk-ant-test-key\n")
        self.toolbox = FakeToolbox()

    def tearDown(self):
        self.tmp.cleanup()

    def analyst(self, client, **kwargs):
        self.keys = []

        def factory(key):
            self.keys.append(key)
            return client
        return ClaudeAnalyst(str(self.key_file), client_factory=factory, **kwargs)


class ClaudeAnalystTest(ClaudeTestBase):
    def test_tool_loop_then_structured_result(self):
        client = FakeClient(
            response("tool_use", text_block("확인"), tool_use("k8s_status", {"namespace": "auction", "kind": "pods"})),
            final())
        result = self.analyst(client).analyze(job(), self.toolbox, [("엔진 Pod", "CrashLoopBackOff")])

        self.assertEqual(result.finding.summary, FINDING["summary"])
        self.assertEqual(result.finding.confidence, "high")
        self.assertEqual((result.tool_calls, result.turns), (1, 2))
        self.assertEqual(result.usage["cache_read_input_tokens"], 4000)
        self.assertEqual(self.keys, ["sk-ant-test-key"])
        self.assertIn(("k8s_status", {"namespace": "auction", "kind": "pods"}), self.toolbox.calls)

        first, second = client.requests
        self.assertEqual(first["model"], "claude-opus-5-5")
        self.assertEqual(first["fallbacks"], "default")
        self.assertEqual(first["betas"], BETAS)
        self.assertEqual(first["output_config"]["format"]["type"], "json_schema")
        self.assertEqual(first["output_config"]["effort"], "medium")
        self.assertEqual(first["cache_control"], {"type": "ephemeral"})
        self.assertNotIn("tool_choice", first)
        # 첫 메시지에 알람, Runbook, 자동 조회 결과가 들어간다
        prompt = first["messages"][0]["content"]
        for expected in ("AuctionEngineDown", "## Runbook: AuctionEngineDown", "A1. 롤백", "CrashLoopBackOff"):
            self.assertIn(expected, prompt)
        # 두 번째 요청: assistant(tool_use) + user(tool_result)
        self.assertEqual(second["message_count"], 3)
        tool_result = second["messages"][2]["content"][0]
        self.assertEqual(tool_result["type"], "tool_result")
        self.assertEqual(tool_result["tool_use_id"], "tu1")

    def test_tool_call_limit_forces_conclusion(self):
        client = FakeClient(
            response("tool_use", tool_use("k8s_status", {}, "a"), tool_use("k8s_status", {}, "b")),
            final())
        result = self.analyst(client, max_tool_calls=1).analyze(job(), self.toolbox, [])
        self.assertEqual(result.tool_calls, 1)
        results = client.requests[1]["messages"][2]["content"]
        self.assertFalse(results[0]["is_error"])
        self.assertTrue(results[1]["is_error"])           # 한도를 넘은 호출은 실행하지 않음
        self.assertEqual(results[-1]["type"], "text")     # 결론을 내라는 안내
        self.assertEqual(client.requests[1]["tool_choice"], {"type": "none"})

    def test_refusal_is_an_error(self):
        client = FakeClient(response("refusal", stop_details=SimpleNamespace(category="cyber")))
        with self.assertRaisesRegex(ClaudeError, "cyber"):
            self.analyst(client).analyze(job(), self.toolbox, [])

    def test_fallback_model_is_reported(self):
        client = FakeClient(final(model="claude-opus-4-8"))
        self.assertEqual(self.analyst(client).analyze(job(), self.toolbox, []).model, "claude-opus-4-8")

    def test_invalid_json_is_an_error(self):
        client = FakeClient(response("end_turn", text_block("분석 결과입니다")))
        with self.assertRaisesRegex(ClaudeError, "JSON"):
            self.analyst(client).analyze(job(), self.toolbox, [])

    def test_timeout(self):
        times = iter([0, 0, 1000])
        client = FakeClient(response("tool_use", tool_use("k8s_status", {})), final())
        analyst = ClaudeAnalyst(str(self.key_file), timeout_seconds=300,
                                client_factory=lambda key: client, clock=lambda: next(times))
        with self.assertRaisesRegex(ClaudeError, "시간 초과"):
            analyst.analyze(job(), self.toolbox, [])

    def test_output_is_masked_and_limited(self):
        finding = {**FINDING, "evidence": [f"근거 {i} token=sk-ant-abcdefghijklmnopqrstuvwxyz" for i in range(10)],
                   "summary": "가" * 1000}
        result = self.analyst(FakeClient(final(finding))).analyze(job(), self.toolbox, [])
        self.assertEqual(len(result.finding.evidence), 6)
        self.assertNotIn("sk-ant-abcdefghijklmnopqrstuvwxyz", " ".join(result.finding.evidence))
        self.assertLessEqual(len(result.finding.summary), 301)

    def test_disabled_without_key(self):
        self.key_file.write_text("")
        self.assertFalse(ClaudeAnalyst(str(self.key_file)).enabled())
        self.assertFalse(ClaudeAnalyst("/nonexistent/api-key").enabled())


class AnalyzeWithClaudeTest(ClaudeTestBase):
    def test_success_message(self):
        analysis = analyze(job(), self.toolbox, self.analyst(FakeClient(final())))
        self.assertEqual(analysis.status, "ok")
        embed = build_message(job(), analysis)["embeds"][0]
        for expected in ("**판단** 엔진이 DB 주소를", "(확신도 높음)", "**근거**", "UnknownHostException",
                         "**권장 조치 (Runbook)**", "• A1: 직전 revision", "**사람이 확인할 것**", "📘"):
            self.assertIn(expected, embed["description"])
        self.assertIn("claude-opus-5-5 · 도구 0회", embed["footer"]["text"])

    def test_api_error_still_posts(self):
        with self.assertLogs("triage_bot.analyzer", "ERROR"):
            analysis = analyze(job(), self.toolbox, self.analyst(FakeClient(RuntimeError("connection reset"))))
        self.assertEqual(analysis.status, "failed")
        self.assertIn("자동 분석 실패 (RuntimeError: connection reset)", analysis.summary)
        self.assertIn("📘", build_message(job(), analysis)["embeds"][0]["description"])

    def test_disabled_message(self):
        self.key_file.write_text("")
        analysis = analyze(job(), self.toolbox, self.analyst(FakeClient()))
        self.assertEqual(analysis.status, "disabled")
        self.assertIn("자동 분석이 꺼져 있어", analysis.summary)

    def test_metrics(self):
        metrics = Metrics()
        metrics.claude_result("ok", 3, {"input_tokens": 10, "cache_read_input_tokens": 20})
        metrics.claude_result("failed")
        text = metrics.render()
        self.assertIn('triage_bot_claude_analyses_total{status="ok"} 1', text)
        self.assertIn('triage_bot_claude_analyses_total{status="failed"} 1', text)
        self.assertIn('triage_bot_claude_tokens_total{type="cache_read_input"} 20', text)
        self.assertIn("triage_bot_claude_tool_calls_total 3", text)


if __name__ == "__main__":
    unittest.main()
