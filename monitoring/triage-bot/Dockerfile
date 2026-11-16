FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --root-user-action=ignore -r requirements.txt

COPY triage_bot triage_bot

# 루트가 아닌 사용자로 실행 (k8s securityContext의 runAsNonRoot와 맞춤)
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["python", "-m", "triage_bot"]
