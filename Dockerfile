# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

COPY src src

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew --no-daemon bootJar \
    && cp build/libs/dogether-*.jar app.jar

FROM eclipse-temurin:25-jre

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive \
       apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 dogether \
    && useradd --uid 10001 --gid dogether --no-create-home \
       --shell /usr/sbin/nologin dogether

WORKDIR /app

COPY --from=builder --chown=dogether:dogether /workspace/app.jar app.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--show-error", "--output", "/dev/null", \
         "--max-time", "3", "http://127.0.0.1:8080/actuator/health"]

STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
