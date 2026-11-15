# Build stage — Maven build of the mono-module artifact (Java 25 toolchain required).
# The Angular UI is NOT bundled here: it is built and served by the separate `frontend`
# container (nginx) in the two-container docker-compose layout.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml lombok.config docker-maven-settings.xml ./
COPY src ./src
# -s docker-maven-settings.xml: Maven Central is blocked from the corporate network, so all
# dependency resolution goes through the internal Artifactory (mirrorOf *).
# -P docker: selects the Linux x86_64 OpenSSL-3 TDLight native classifier (macOS dev default).
RUN mvn -B -s docker-maven-settings.xml -P docker -DskipTests package

# Runtime stage
FROM eclipse-temurin:25-jre
# TDLight native (linux_amd64_gnu_ssl3) links OpenSSL 3 + zlib at runtime; install them so the
# personal-Telegram ingestion can load the native library when SIGNAL_ANALYSIS_INGESTION_ENABLED.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl3 zlib1g wget \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/traderbro.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]