# 빌드 스테이지: Gradle로 jar 빌드
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src src
RUN ./gradlew --no-daemon bootJar

# 실행 스테이지: 빌드된 jar만 가지고 실행 (빌드 도구는 이미지에 안 남김)
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# OpenTelemetry Java 에이전트: HTTP 요청, DB 쿼리, Redis 호출을 자동으로 트레이스로 기록하고,
# 로그에 trace_id/span_id를 넣어준다. 이미지에는 항상 포함하되, JAVA_TOOL_OPTIONS에
# -javaagent를 지정한 환경(k8s, 로컬 로그 스택)에서만 켜진다.
# 버전을 고정하고 체크섬으로 파일이 바뀌지 않았는지 검증한다.
ADD --checksum=sha256:e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.33.1/opentelemetry-javaagent.jar \
    /app/opentelemetry-javaagent.jar

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
