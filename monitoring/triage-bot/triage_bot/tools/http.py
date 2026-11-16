"""도구들이 공통으로 쓰는 GET 요청 (JSON 응답). 테스트에서는 get_json을 바꿔 끼운다."""
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request


class ToolError(RuntimeError):
    """도구 실행 실패. 메시지는 Claude가 읽고 쿼리를 고칠 수 있게 구체적으로 쓴다."""


def get_json(url: str, params: dict | None = None, headers: dict | None = None,
             context: ssl.SSLContext | None = None, timeout: float = 10.0) -> dict:
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"Accept": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as exc:
        detail = exc.read()[:500].decode("utf-8", "replace")
        try:   # Prometheus/Loki/k8s 모두 에러 본문에 이유를 담아 준다
            body = json.loads(detail)
            detail = body.get("error") or body.get("message") or detail
        except ValueError:
            pass
        raise ToolError(f"HTTP {exc.code}: {detail.strip()}") from None
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ToolError(f"연결 실패: {getattr(exc, 'reason', exc)}") from None
    except ValueError:
        raise ToolError("JSON이 아닌 응답") from None
