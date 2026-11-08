# Build stage — Maven build of the mono-module artifact (Java 25 toolchain required).
# The Angular UI is NOT bundled here: it is built and served by the separate `frontend`
# container (nginx) in the two-container docker-compose layout.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml lombok.config docker-maven-settings.xml ./
COPY src ./src
# -s docker-maven-settings.xml: Maven Central is blocked from the corporate network, so all
# dependency resolution goes through the internal Artifactory (mirrorOf *).
RUN mvn -B -s docker-maven-settings.xml -DskipTests package

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/traderbro.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]