# 빌드 컨텍스트는 레포 루트다 - 배포된 버전과 같은 Runbook(docs/runbooks)을 이미지에 함께 넣기 위해서.
#   docker build -f monitoring/triage-bot/Dockerfile .
# 필요한 파일만 보내도록 Dockerfile.dockerignore(같은 폴더)에서 허용 목록을 관리한다.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY monitoring/triage-bot/requirements.txt .
RUN pip install --no-cache-dir --root-user-action=ignore -r requirements.txt

COPY monitoring/triage-bot/triage_bot triage_bot
COPY docs/runbooks runbooks

# 루트가 아닌 사용자로 실행 (k8s securityContext의 runAsNonRoot와 맞춤)
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["python", "-m", "triage_bot"]
