"""환경변수 설정. 비밀값(webhook URL 등)은 환경변수가 아니라 파일(Secret 마운트)로 받는다."""
import os
from dataclasses import dataclass, field


def _int(name: str, default: int) -> int:
    value = os.environ.get(name, "").strip()
    return int(value) if value else default


def _set(name: str, default: str) -> frozenset[str]:
    raw = os.environ.get(name, default)
    return frozenset(v.strip() for v in raw.split(",") if v.strip())


@dataclass(frozen=True)
class Config:
    port: int = 8080
    # Alertmanager의 alertmanager-discord Secret을 그대로 마운트해서 같은 채널에 게시한다
    discord_webhook_url_file: str = "/etc/triage-bot/discord/discord-webhook-url"
    # 같은 알람 그룹을 다시 분석하지 않는 시간 (Alertmanager는 critical을 1시간마다 다시 보낸다)
    cooldown_minutes: int = 30
    # 하루(KST) 분석 횟수 상한 - Claude 호출 비용 상한 역할
    daily_limit: int = 20
    queue_size: int = 20
    target_severities: frozenset[str] = field(default_factory=lambda: frozenset({"critical"}))
    # true면 Discord에 보내지 않고 로그로만 출력 (로컬 실행용)
    dry_run: bool = False
    # 조회 도구 대상 (같은 monitoring 네임스페이스의 Service 이름)
    prometheus_url: str = "http://prometheus:9090"
    loki_url: str = "http://loki:3100"
    # 이미지에 함께 넣은 docs/runbooks
    runbook_dir: str = "/app/runbooks"

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            port=_int("PORT", 8080),
            discord_webhook_url_file=os.environ.get(
                "DISCORD_WEBHOOK_URL_FILE", cls.discord_webhook_url_file),
            cooldown_minutes=_int("COOLDOWN_MINUTES", 30),
            daily_limit=_int("DAILY_LIMIT", 20),
            queue_size=_int("QUEUE_SIZE", 20),
            target_severities=_set("TARGET_SEVERITIES", "critical"),
            dry_run=os.environ.get("DRY_RUN", "").lower() in {"1", "true", "yes"},
            prometheus_url=os.environ.get("PROMETHEUS_URL", cls.prometheus_url).rstrip("/"),
            loki_url=os.environ.get("LOKI_URL", cls.loki_url).rstrip("/"),
            runbook_dir=os.environ.get("RUNBOOK_DIR", cls.runbook_dir),
        )
