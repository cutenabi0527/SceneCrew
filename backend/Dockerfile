# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src ./src
# 테스트는 Jenkins의 backend 테스트 단계(infra/app-server/scripts/test-backend.sh)에서 Docker와 함께 실행한다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/scenecrew-0.0.1-SNAPSHOT.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
# ffmpeg: 올린 음성의 실제 길이를 ffprobe로 잰다 (backend/docs/file-storage.md).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 scenecrew \
    && useradd --uid 10001 --gid scenecrew --no-create-home --shell /usr/sbin/nologin scenecrew
WORKDIR /app
COPY --from=build --chown=scenecrew:scenecrew /workspace/app.jar ./app.jar
ENV SPRING_PROFILES_ACTIVE=local \
    SERVER_PORT=8080 \
    TZ=UTC
USER scenecrew
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
