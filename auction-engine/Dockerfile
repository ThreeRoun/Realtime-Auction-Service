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
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
