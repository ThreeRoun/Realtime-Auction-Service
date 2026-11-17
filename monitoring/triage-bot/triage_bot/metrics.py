"""Prometheus 지표 (/metrics). 라이브러리 없이 텍스트 형식으로 직접 출력한다."""
import threading
from collections import Counter


class Metrics:
    def __init__(self):
        self._lock = threading.Lock()
        self._webhooks = 0
        self._jobs: Counter[str] = Counter()
        self._duration_sum = 0.0
        self._duration_count = 0
        self.queue_size = lambda: 0

    def webhook_received(self) -> None:
        with self._lock:
            self._webhooks += 1

    def job_result(self, result: str) -> None:
        with self._lock:
            self._jobs[result] += 1

    def job_duration(self, seconds: float) -> None:
        with self._lock:
            self._duration_sum += seconds
            self._duration_count += 1

    def render(self) -> str:
        with self._lock:
            lines = [
                "# HELP triage_bot_webhooks_received_total Alertmanager webhook 수신 횟수",
                "# TYPE triage_bot_webhooks_received_total counter",
                f"triage_bot_webhooks_received_total {self._webhooks}",
                "# HELP triage_bot_jobs_total 분석 작업 결과별 횟수",
                "# TYPE triage_bot_jobs_total counter",
            ]
            for result in sorted(self._jobs):
                lines.append(f'triage_bot_jobs_total{{result="{result}"}} {self._jobs[result]}')
            lines += [
                "# HELP triage_bot_job_duration_seconds 분석 작업 처리 시간",
                "# TYPE triage_bot_job_duration_seconds summary",
                f"triage_bot_job_duration_seconds_sum {self._duration_sum}",
                f"triage_bot_job_duration_seconds_count {self._duration_count}",
                "# HELP triage_bot_queue_size 처리 대기 중인 작업 수",
                "# TYPE triage_bot_queue_size gauge",
                f"triage_bot_queue_size {self.queue_size()}",
            ]
        return "\n".join(lines) + "\n"
