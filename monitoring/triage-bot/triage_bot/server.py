"""HTTP 서버와 작업 처리.

    POST /alerts   Alertmanager webhook 수신 → 바로 200 응답 후 큐에 넣는다 (Alertmanager를 기다리게 하지 않음)
    GET  /healthz  liveness/readiness
    GET  /metrics  Prometheus 지표

작업은 워커 스레드 1개가 순서대로 처리한다 (분석이 느려도 수신은 막히지 않고, Claude 호출이 동시에 몰리지 않음).
"""
import json
import logging
import queue
import signal
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import dedup
from .alerts import Job, PayloadError, parse_payload
from .analyzer import analyze
from .config import Config
from .discord import DiscordClient, DiscordError, build_message
from .metrics import Metrics
from .tools import Toolbox

log = logging.getLogger("triage_bot")
MAX_BODY_BYTES = 1024 * 1024
_STOP = object()


class JsonFormatter(logging.Formatter):
    """Loki에서 필드로 검색할 수 있게 JSON 한 줄로 출력한다."""

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "ts": datetime.fromtimestamp(record.created, timezone.utc).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "msg": record.getMessage(),
        }
        entry.update(getattr(record, "fields", {}))
        if record.exc_info:
            entry["error"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False)


class TriageBot:
    def __init__(self, config: Config, discord: DiscordClient | None = None, toolbox: Toolbox | None = None):
        self.config = config
        self.toolbox = toolbox or Toolbox(config.prometheus_url, config.loki_url, config.runbook_dir)
        self.metrics = Metrics()
        self.gate = dedup.Gate(timedelta(minutes=config.cooldown_minutes), config.daily_limit)
        self.discord = discord or DiscordClient(config.discord_webhook_url_file)
        self.jobs: queue.Queue = queue.Queue(maxsize=config.queue_size)
        self.metrics.queue_size = self.jobs.qsize

    # --- 수신 ---
    def receive(self, payload: object) -> str:
        """webhook 한 건을 받아 큐에 넣을지 결정한다. 결과 문자열은 지표/로그용."""
        self.metrics.webhook_received()
        job = parse_payload(payload, self.config.target_severities)
        if job is None:
            return self._record(None, "skipped_no_target")

        decision = self.gate.check(job, datetime.now(timezone.utc))
        if decision != dedup.ACCEPT:
            return self._record(job, decision)
        try:
            self.jobs.put_nowait(job)
        except queue.Full:
            return self._record(job, "dropped_queue_full", logging.WARNING)
        log.info("작업 등록", extra={"fields": {"job": job.job_id, "alert": job.title}})
        return "queued"

    def _record(self, job: Job | None, result: str, level: int = logging.INFO) -> str:
        self.metrics.job_result(result)
        fields = {"result": result}
        if job:
            fields.update(job=job.job_id, alert=job.title)
        log.log(level, "작업 건너뜀", extra={"fields": fields})
        return result

    # --- 처리 ---
    def process(self, job: Job) -> str:
        started = time.monotonic()
        fields = {"job": job.job_id, "alert": job.title}
        try:
            message = build_message(job, analyze(job, self.toolbox))
            if self.config.dry_run:
                log.info("dry-run: Discord 게시 생략", extra={"fields": {**fields, "message": message}})
            else:
                self.discord.post(message)
            result = "posted"
            log.info("게시 완료", extra={"fields": fields})
        except DiscordError as exc:
            result = "failed"
            log.error("Discord 게시 실패", extra={"fields": {**fields, "reason": str(exc)}})
        except Exception:   # 한 작업의 예외로 워커가 죽지 않게
            result = "failed"
            log.exception("작업 처리 중 예외", extra={"fields": fields})
        self.metrics.job_result(result)
        self.metrics.job_duration(time.monotonic() - started)
        return result

    def worker(self) -> None:
        while True:
            job = self.jobs.get()
            if job is _STOP:
                return
            self.process(job)

    def stop_worker(self) -> None:
        # 큐가 가득 차 있어도 종료 신호는 넣는다 (대기 중인 작업은 처리하고 끝난다)
        self.jobs.put(_STOP)


def make_handler(bot: TriageBot) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "triage-bot"

        def do_GET(self) -> None:
            if self.path == "/healthz":
                self._send(200, b"ok\n", "text/plain")
            elif self.path == "/metrics":
                self._send(200, bot.metrics.render().encode(), "text/plain; version=0.0.4")
            else:
                self._send(404, b"not found\n", "text/plain")

        def do_POST(self) -> None:
            if self.path != "/alerts":
                self._send(404, b"not found\n", "text/plain")
                return
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY_BYTES:
                self._send(413 if length > MAX_BODY_BYTES else 400, b"bad request\n", "text/plain")
                return
            try:
                result = bot.receive(json.loads(self.rfile.read(length)))
            except (json.JSONDecodeError, PayloadError) as exc:
                log.warning("잘못된 webhook 요청", extra={"fields": {"reason": str(exc)}})
                self._send(400, b"bad request\n", "text/plain")
                return
            self._send(200, json.dumps({"result": result}).encode(), "application/json")

        def _send(self, code: int, body: bytes, content_type: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args) -> None:   # 기본 접근 로그(probe 요청 포함)는 끈다
            pass

    return Handler


def main() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(level=logging.INFO, handlers=[handler])

    config = Config.from_env()
    bot = TriageBot(config)
    server = ThreadingHTTPServer(("0.0.0.0", config.port), make_handler(bot))
    worker = threading.Thread(target=bot.worker, name="worker")
    worker.start()

    def shutdown(signum, _frame):
        log.info("종료 신호 수신", extra={"fields": {"signal": signum}})
        threading.Thread(target=server.shutdown).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    log.info("시작", extra={"fields": {
        "port": config.port, "dry_run": config.dry_run, "cooldown_minutes": config.cooldown_minutes,
        "daily_limit": config.daily_limit, "severities": sorted(config.target_severities)}})
    server.serve_forever()
    bot.stop_worker()
    worker.join(timeout=20)
    log.info("종료")


if __name__ == "__main__":
    main()
