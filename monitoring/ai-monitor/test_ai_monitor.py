import io
import os
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_monitor as am  # noqa: E402

# 가짜 키는 소스에 리터럴로 두지 않고 실행 중에 조립한다(시크릿 스캐너 오탐 방지)
FAKE_KEY = "sk-ant-" + "x" * 24
FAKE_JWT = "eyJ" + "a" * 12 + "." + "b" * 12 + "." + "c" * 12


def calm_snapshot() -> dict:
    return {
        "metrics": {
            "window_minutes": 15,
            "bids_placed": {"current": 3.0, "baseline_per_min": 0.2},
            "bids_rejected_by_reason": {"current": {"amount_too_low": 2.0}, "baseline_per_min": {"amount_too_low": 0.1}},
            "http_5xx": {"current": 0.0, "baseline_per_min": 0.0},
            "bid_endpoint_p99_seconds": 0.05,
            "instances_up": 1.0,
            "hikari_pending_max": 0.0,
            "jvm_heap_used_ratio": 0.4,
        },
        "logs": {"counts_by_level": {}, "groups": []},
    }


class ScrubTest(unittest.TestCase):
    def test_masks_secrets(self):
        text = f"key={FAKE_KEY} jwt={FAKE_JWT} Authorization: Bearer abcdefghij1234 mail me a.b@c.com password=hunter22"
        out = am.scrub(text)
        for leaked in (FAKE_KEY, FAKE_JWT, "abcdefghij1234", "a.b@c.com", "hunter22"):
            self.assertNotIn(leaked, out)

    def test_masks_exact_env_secret_even_without_known_pattern(self):
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": "plain-secret-value-123"}):
            self.assertNotIn("plain-secret-value-123", am.scrub("oops plain-secret-value-123 oops"))

    def test_masks_discord_webhook(self):
        fake_url = "https://discord" + ".com/api/web" + "hooks/123/abcdef"  # 소스에 URL 리터럴을 두지 않는다
        out = am.scrub(fake_url)
        self.assertNotIn("abcdef", out)

    def test_shortens_user_uuid(self):
        out = am.scrub("user 123e4567-e89b-12d3-a456-426614174000 bid")
        self.assertIn("123e4567…", out)
        self.assertNotIn("426614174000", out)

    def test_emit_never_prints_env_key(self):
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": FAKE_KEY}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                am.emit(f"debug {FAKE_KEY}")
        self.assertNotIn(FAKE_KEY, buf.getvalue())

    def test_sanitize_ai_text_neutralizes_links_and_mentions(self):
        out = am.sanitize_ai_text("see [x](https://evil.example/a) and https://evil.example/b ping @someone ![i](http://t/p.png)")
        self.assertNotIn("evil.example", out)
        self.assertNotIn("http", out)
        self.assertNotRegex(out, r"@(?=\w)")


class DotenvTest(unittest.TestCase):
    def test_loads_without_overriding_or_printing(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / ".env"
            path.write_text(f"﻿# comment\nANTHROPIC_API_KEY=\"{FAKE_KEY}\"\nEXISTING=new\nEMPTY=\n", encoding="utf-8")
            buf = io.StringIO()
            with mock.patch.dict(os.environ, {"EXISTING": "old"}, clear=False), redirect_stdout(buf):
                os.environ.pop("ANTHROPIC_API_KEY", None)
                am.load_dotenv(path)
                self.assertEqual(os.environ["ANTHROPIC_API_KEY"], FAKE_KEY)
                self.assertEqual(os.environ["EXISTING"], "old")
                self.assertNotIn("EMPTY", os.environ)
                os.environ.pop("ANTHROPIC_API_KEY", None)
        self.assertEqual(buf.getvalue(), "")

    def test_missing_file_is_ignored(self):
        am.load_dotenv(Path("definitely/not/here/.env"))


class RuleFilterTest(unittest.TestCase):
    def test_spike(self):
        self.assertFalse(am.spike(4, 0.0, 15))        # 절대량 부족
        self.assertTrue(am.spike(5, 0.0, 15))         # 거의 없던 이벤트가 5건
        self.assertFalse(am.spike(10, 1.0, 15))       # 기준선(15건/구간)의 3배 미만
        self.assertTrue(am.spike(60, 1.0, 15))
        self.assertFalse(am.spike(None, 1.0, 15))

    def test_calm_has_no_reasons(self):
        self.assertEqual(am.notable_reasons(calm_snapshot()), [])

    def test_flags_each_signal(self):
        s = calm_snapshot()
        s["metrics"]["http_5xx"]["current"] = 2.0
        s["metrics"]["instances_up"] = 0.0
        s["metrics"]["bids_rejected_by_reason"]["current"]["cooldown_active"] = 40.0
        s["logs"]["counts_by_level"] = {"ERROR": 3}
        reasons = " / ".join(am.notable_reasons(s))
        for expected in ("5xx", "인스턴스", "cooldown_active", "ERROR"):
            self.assertIn(expected, reasons)

    def test_missing_instances_up_is_flagged(self):
        s = calm_snapshot()
        s["metrics"]["instances_up"] = None
        self.assertTrue(am.notable_reasons(s))


class LogCollectionTest(unittest.TestCase):
    def test_groups_similar_messages_and_trims(self):
        lines = [
            ["1", '{"level":"ERROR","logger_name":"a.B","message":"fail for 123e4567-e89b-12d3-a456-426614174000 after 17ms","stack_trace":"ex\\n at x\\n at y"}'],
            ["2", '{"level":"ERROR","logger_name":"a.B","message":"fail for 223e4567-e89b-12d3-a456-426614174000 after 90ms"}'],
            ["3", "not json at all"],
        ]
        responses = [
            {"data": {"result": [{"metric": {"level": "ERROR"}, "value": [0, "2"]}]}},
            {"data": {"result": [{"stream": {"level": "ERROR"}, "values": lines}]}},
        ]
        cfg = am.parse_args([])
        with mock.patch.object(am, "http_get_json", side_effect=responses):
            logs = am.collect_logs(cfg)
        self.assertEqual(logs["counts_by_level"], {"ERROR": 2})
        top = logs["groups"][0]
        self.assertEqual(top["count"], 2)
        self.assertIn("ex", top["stack_head"])
        self.assertEqual(len(logs["groups"]), 2)


class MetricCollectionTest(unittest.TestCase):
    def test_counter_increases_are_rounded_to_whole_events(self):
        def fake_query(_base, query):
            if "offset" in query:
                return []
            return [{"metric": {"reason": "amount_too_low"}, "value": [0, "6.4273"]}] if "by (reason)" in query \
                else [{"metric": {}, "value": [0, "26.7803"]}]
        with mock.patch.object(am, "prom_query", side_effect=fake_query):
            m = am.collect_metrics(am.parse_args([]))
        self.assertEqual(m["bids_rejected_by_reason"]["current"], {"amount_too_low": 6})
        self.assertEqual(m["bids_placed"]["current"], 27)


class IssueBodyTest(unittest.TestCase):
    def test_body_has_checkboxes_and_no_raw_logs_or_secrets(self):
        analysis = {
            "anomaly": True, "severity": "warning", "title": "t", "confidence": "medium",
            "summary": f"요약 {FAKE_KEY} @team https://x.example/y",
            "evidence": ["cooldown_active 40건 (기준선 0.1/분)"],
            "suspected_cause": "연타 의심",
            "suggested_actions": ["프론트 중복 클릭 방지 확인", "해당 유저 입찰 이력 조회"],
        }
        body = am.render_issue_body(analysis, calm_snapshot(), ["입찰 거절(cooldown_active) 급증: 40건"])
        self.assertIn("- [ ] 프론트 중복 클릭 방지 확인", body)
        self.assertNotIn(FAKE_KEY, body)
        self.assertNotIn("x.example", body)
        self.assertNotRegex(body, r"@(?=\w)")
        self.assertNotIn("stack", body)


class SourceHygieneTest(unittest.TestCase):
    def test_no_api_key_literals_in_this_directory(self):
        here = Path(__file__).resolve().parent
        pattern = re.compile(r"sk-ant-[A-Za-z0-9_\-]{6,}")
        for path in here.rglob("*"):
            if path.is_file() and path.suffix in {".py", ".md", ".txt", ".json", ".env"} and "output" not in path.parts:
                self.assertIsNone(pattern.search(path.read_text(encoding="utf-8", errors="ignore")), str(path))


if __name__ == "__main__":
    unittest.main()
