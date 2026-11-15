import json
import os
from pathlib import Path

import anthropic

# 프로젝트 최상위 폴더 기준으로 JSON 파일 찾기
BASE_DIR = Path(__file__).resolve().parent.parent
RESULT_FILE = BASE_DIR / "frontend" / "failure-results.json"

def main():
    # 1. API 키 확인
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("[오류] ANTHROPIC_API_KEY 환경 변수가 설정되지 않았습니다.")
        return

    # 2. Playwright 테스트 결과 읽기
    if not RESULT_FILE.exists():
        print(f"[오류] 테스트 결과 파일이 없습니다: {RESULT_FILE}")
        return

    with open(RESULT_FILE, "r", encoding="utf-8-sig") as file:
        results = json.load(file)

    # 3. 실패한 테스트 정보 추출
    failures = []

    def collect_failures(suites):
        for suite in suites:
            for spec in suite.get("specs", []):
                for test in spec.get("tests", []):
                    if test.get("status") != "unexpected":
                        continue

                    errors = []
                    for result in test.get("results", []):
                        for error in result.get("errors", []):
                            if error.get("message"):
                                errors.append(error["message"])

                    failures.append({
                        "title": spec.get("title"),
                        "file": spec.get("file"),
                        "errors": errors,
                    })

            collect_failures(suite.get("suites", []))

    collect_failures(results.get("suites", []))

    if not failures:
        print("[안내] 실패한 테스트가 없습니다.")
        return

    print(f"[확인] 실패한 테스트 {len(failures)}개 발견")
    print("[진행] Claude API로 실패 원인 분석 중...")

    # 4. Claude API에 실패 정보 전달
    client = anthropic.Anthropic()

    response = client.messages.create(
        model="claude-haiku-5-5",
        max_tokens=2000,
        messages=[
            {
                "role": "user",
                "content": (
                    "너는 Playwright E2E 테스트 분석 전문가야.\n"
                    "다음 테스트 실패 정보를 분석해 줘.\n\n"
                    f"{json.dumps(failures, ensure_ascii=False, indent=2)}\n\n"
                    "다음 형식으로 한국어로 답변해 줘.\n"
                    "1. 실패한 테스트\n"
                    "2. 실패 원인\n"
                    "3. 해결 방법\n"
                    "4. 추가 확인 사항\n"
                    "로그만으로 확정할 수 없는 내용은 추측이라고 명시해 줘."
                ),
            }
        ],
    )

    # 5. 분석 결과 출력
    print("\n========== Claude 분석 결과 ==========\n")

    for block in response.content:
        if block.type == "text":
            print(block.text)

    print("\n======================================")

    print(
        f"토큰 사용량: 입력 {response.usage.input_tokens}"
        f" / 출력 {response.usage.output_tokens}"
    )


if __name__ == "__main__":
    main()