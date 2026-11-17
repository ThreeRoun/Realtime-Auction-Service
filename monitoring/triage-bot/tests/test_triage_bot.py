import io
import json
import logging
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from triage_bot import dedup  # noqa: E402
from triage_bot.alerts import PayloadError, parse_payload  # noqa: E402
from triage_bot.analyzer import analyze  # noqa: E402
from triage_bot.config import Config  # noqa: E402
from triage_bot.discord import DiscordClient, DiscordError, build_message  # noqa: E402
from triage_bot.server import JsonFormatter, TriageBot, make_handler  # noqa: E402

CRITICAL = frozenset({"critical"})
RUNBOOK = "https://github.com/ThreeRoun/Realtime-Auction-Service/blob/baseline/docs/runbooks/AuctionEngineDown.md"


def alert(name="AuctionEngineDown", severity="critical", status="firing", fingerprint="fp1", **annotations):
    return {
        "status": status,
        "labels": {"alertname": name, "severity": severity, "service": "auction-engine"},
        "annotations": {"summary": f"{name} 요약", "description": "설명", **annotations},
        "startsAt": "2026-11-16T03:18:41.123456789Z",
        "endsAt": "0001-01-01T00:00:00Z",
        "fingerprint": fingerprint,
    }


def payload(*alerts, group_key='{}:{alertname="AuctionEngineDown"}', status="firing"):
    """Alertmanager webhook version 4 형식."""
    return {
        "version": "4", "groupKey": group_key, "truncatedAlerts": 0, "status": status,
        "receiver": "triage-bot", "groupLabels": {"alertname": "AuctionEngineDown"},
        "commonLabels": {"alertname": "AuctionEngineDown", "severity": "critical"},
        "commonAnnotations": {}, "externalURL": "http://alertmanager:9093",
        "alerts": list(alerts) or [alert()],
    }


class ParsePayloadTest(unittest.TestCase):
    def test_keeps_firing_critical_alerts(self):
        job = parse_payload(payload(alert(runbook_url=RUNBOOK)), CRITICAL)
        self.assertEqual(job.title, "AuctionEngineDown")
        self.assertEqual(job.fingerprints, {"fp1"})
        # 나노초 시각도 파싱된다
        self.assertEqual(job.alerts[0].starts_at, datetime(2026, 11, 16, 3, 18, 41, 123456, timezone.utc))

    def test_ignores_resolved_and_other_severities(self):
        self.assertIsNone(parse_payload(payload(alert(status="resolved")), CRITICAL))
        self.assertIsNone(parse_payload(payload(alert(severity="warning")), CRITICAL))

    def test_mixed_group_keeps_only_targets(self):
        job = parse_payload(payload(alert(fingerprint="a"), alert(severity="warning", fingerprint="b"),
                                    alert(status="resolved", fingerprint="c")), CRITICAL)
        self.assertEqual(job.fingerprints, {"a"})

    def test_title_with_several_alert_names(self):
        job = parse_payload(payload(alert("BidEndpoint5xxRate", fingerprint="a"),
                                    alert("AuctionEngineHigh5xxRate", fingerprint="b")), CRITICAL)
        self.assertEqual(job.title, "AuctionEngineHigh5xxRate 외 1건")

    def test_rejects_non_alertmanager_body(self):
        for body in ({}, [], {"alerts": "x"}, "text"):
            with self.assertRaises(PayloadError):
                parse_payload(body, CRITICAL)


class GateTest(unittest.TestCase):
    NOW = datetime(2026, 11, 16, 3, 0, tzinfo=timezone.utc)

    def job(self, *fingerprints, group_key="g1"):
        alerts = [alert(fingerprint=f) for f in fingerprints] or [alert()]
        return parse_payload(payload(*alerts, group_key=group_key), CRITICAL)

    def test_same_alert_within_cooldown_is_skipped(self):
        gate = dedup.Gate(timedelta(minutes=30), daily_limit=10)
        self.assertEqual(gate.check(self.job("a"), self.NOW), dedup.ACCEPT)
        # Alertmanager가 1시간 전에 다시 보내는 경우 등
        self.assertEqual(gate.check(self.job("a"), self.NOW + timedelta(minutes=29)), dedup.SKIP_COOLDOWN)
        self.assertEqual(gate.check(self.job("a"), self.NOW + timedelta(minutes=30)), dedup.ACCEPT)

    def test_new_alert_in_group_is_analyzed_again(self):
        gate = dedup.Gate(timedelta(minutes=30), daily_limit=10)
        gate.check(self.job("a"), self.NOW)
        self.assertEqual(gate.check(self.job("a", "b"), self.NOW + timedelta(minutes=5)), dedup.ACCEPT)
        # a, b 모두 분석했으므로 이후 같은 조합은 건너뜀
        self.assertEqual(gate.check(self.job("b"), self.NOW + timedelta(minutes=6)), dedup.SKIP_COOLDOWN)

    def test_other_groups_are_independent(self):
        gate = dedup.Gate(timedelta(minutes=30), daily_limit=10)
        gate.check(self.job("a", group_key="g1"), self.NOW)
        self.assertEqual(gate.check(self.job("a", group_key="g2"), self.NOW), dedup.ACCEPT)

    def test_daily_limit_resets_at_kst_midnight(self):
        gate = dedup.Gate(timedelta(minutes=30), daily_limit=2)
        before_midnight = datetime(2026, 11, 16, 14, 50, tzinfo=timezone.utc)   # KST 23:50
        self.assertEqual(gate.check(self.job(group_key="g1"), before_midnight), dedup.ACCEPT)
        self.assertEqual(gate.check(self.job(group_key="g2"), before_midnight), dedup.ACCEPT)
        self.assertEqual(gate.check(self.job(group_key="g3"), before_midnight), dedup.SKIP_DAILY_LIMIT)
        after_midnight = before_midnight + timedelta(minutes=15)                 # KST 00:05
        self.assertEqual(gate.check(self.job(group_key="g3"), after_midnight), dedup.ACCEPT)


class MessageTest(unittest.TestCase):
    def test_message_has_runbook_and_blocks_mentions(self):
        job = parse_payload(payload(alert(runbook_url=RUNBOOK, summary="@everyone 엔진 다운")), CRITICAL)
        message = build_message(job, analyze(job))
        embed = message["embeds"][0]
        self.assertEqual(message["allowed_mentions"], {"parse": []})
        self.assertIn("트리아지: AuctionEngineDown", embed["title"])
        self.assertIn(f"[AuctionEngineDown Runbook]({RUNBOOK})", embed["description"])
        self.assertIn("(시작 11-16 12:18:41)", embed["description"])   # KST로 표시
        self.assertIn(job.job_id, embed["footer"]["text"])

    def test_long_description_is_truncated(self):
        alerts = [alert(fingerprint=str(i), summary="x" * 500) for i in range(20)]
        job = parse_payload(payload(*alerts), CRITICAL)
        self.assertLessEqual(len(build_message(job, analyze(job))["embeds"][0]["description"]), 4000)


class FakeHTTPError(urllib.error.HTTPError):
    def __init__(self, code, body=b""):
        super().__init__("https://discord.test", code, "error", {}, io.BytesIO(body))


class DiscordClientTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.url_file = Path(self.tmp.name) / "url"
        self.url_file.write_text("https://discord.test/api/webhooks/1/abc\n")
        self.sleeps = []
        self.client = DiscordClient(str(self.url_file), sleep=self.sleeps.append)

    def tearDown(self):
        self.tmp.cleanup()

    def test_retries_rate_limit_then_succeeds(self):
        responses = [FakeHTTPError(429, b'{"retry_after": 1.5}'), mock.MagicMock()]

        def fake_urlopen(request, timeout):
            self.assertEqual(request.get_header("User-agent"), "triage-bot (Realtime-Auction-Service)")
            result = responses.pop(0)
            if isinstance(result, Exception):
                raise result
            return result

        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            self.client.post({"content": "x"})
        self.assertEqual(self.sleeps, [1.5])

    def test_client_error_is_not_retried(self):
        with mock.patch("urllib.request.urlopen", side_effect=FakeHTTPError(404)) as urlopen:
            with self.assertRaisesRegex(DiscordError, "404"):
                self.client.post({"content": "x"})
        self.assertEqual(urlopen.call_count, 1)

    def test_missing_url_file(self):
        self.url_file.unlink()
        with self.assertRaisesRegex(DiscordError, "읽을 수 없음"):
            self.client.post({"content": "x"})

    def test_error_message_does_not_contain_webhook_url(self):
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.URLError("boom")):
            with self.assertRaises(DiscordError) as ctx:
                self.client.post({"content": "x"})
        self.assertNotIn("webhooks", str(ctx.exception))


class FakeDiscord:
    def __init__(self, fail=False):
        self.messages = []
        self.fail = fail

    def post(self, message):
        if self.fail:
            raise DiscordError("Discord 응답 500")
        self.messages.append(message)


class ServerTest(unittest.TestCase):
    """실제 HTTP 서버를 띄워 수신 → 큐 → 워커 → 게시 흐름을 확인한다."""

    def setUp(self):
        logging.disable(logging.CRITICAL)
        self.discord = FakeDiscord()
        self.bot = TriageBot(Config(cooldown_minutes=30, daily_limit=10), discord=self.discord)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bot))
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        logging.disable(logging.NOTSET)

    def request(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read().decode()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read().decode()

    def drain(self):
        while not self.bot.jobs.empty():
            self.bot.process(self.bot.jobs.get_nowait())

    def test_alert_is_posted_once(self):
        status, body = self.request("POST", "/alerts", payload(alert(runbook_url=RUNBOOK)))
        self.assertEqual((status, json.loads(body)["result"]), (200, "queued"))
        # Alertmanager가 같은 알람을 다시 보내도 한 번만 분석
        _, body = self.request("POST", "/alerts", payload(alert(runbook_url=RUNBOOK)))
        self.assertEqual(json.loads(body)["result"], "skipped_cooldown")
        self.drain()
        self.assertEqual(len(self.discord.messages), 1)

        _, metrics = self.request("GET", "/metrics")
        self.assertIn("triage_bot_webhooks_received_total 2", metrics)
        self.assertIn('triage_bot_jobs_total{result="posted"} 1', metrics)
        self.assertIn('triage_bot_jobs_total{result="skipped_cooldown"} 1', metrics)

    def test_resolved_notification_is_ignored(self):
        _, body = self.request("POST", "/alerts", payload(alert(status="resolved"), status="resolved"))
        self.assertEqual(json.loads(body)["result"], "skipped_no_target")
        self.assertTrue(self.bot.jobs.empty())

    def test_bad_requests(self):
        self.assertEqual(self.request("POST", "/alerts", {"hello": 1})[0], 400)
        self.assertEqual(self.request("POST", "/nope", payload())[0], 404)
        req = urllib.request.Request(self.base + "/alerts", data=b"{not json", method="POST")
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 400)

    def test_health(self):
        self.assertEqual(self.request("GET", "/healthz"), (200, "ok\n"))

    def test_queue_full_drops_job(self):
        bot = TriageBot(Config(queue_size=1), discord=self.discord)
        self.assertEqual(bot.receive(payload(alert(fingerprint="a"), group_key="g1")), "queued")
        self.assertEqual(bot.receive(payload(alert(fingerprint="b"), group_key="g2")), "dropped_queue_full")

    def test_discord_failure_is_recorded_and_worker_survives(self):
        bot = TriageBot(Config(), discord=FakeDiscord(fail=True))
        bot.receive(payload())
        self.assertEqual(bot.process(bot.jobs.get_nowait()), "failed")
        self.assertIn('triage_bot_jobs_total{result="failed"} 1', bot.metrics.render())


class LoggingTest(unittest.TestCase):
    def test_json_log_line(self):
        record = logging.LogRecord("triage_bot", logging.INFO, __file__, 1, "게시 완료", None, None)
        record.fields = {"job": "abcd1234"}
        line = json.loads(JsonFormatter().format(record))
        self.assertEqual((line["level"], line["msg"], line["job"]), ("INFO", "게시 완료", "abcd1234"))

    def test_dry_run_does_not_post(self):
        discord = FakeDiscord()
        bot = TriageBot(Config(dry_run=True), discord=discord)
        bot.receive(payload())
        with redirect_stdout(io.StringIO()):
            self.assertEqual(bot.process(bot.jobs.get_nowait()), "posted")
        self.assertEqual(discord.messages, [])


if __name__ == "__main__":
    unittest.main()
