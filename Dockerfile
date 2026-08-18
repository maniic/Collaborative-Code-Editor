# ──────────────────────────────────────────────────────────────
# Stage 1: Build the Spring Boot jar using the Gradle wrapper
#
# Must be a JDK 21 image: build.gradle.kts pins the Gradle
# toolchain to Java 21, so an older JDK here would force Gradle
# to download a second JDK mid-build.
# ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

# Copy only the files needed for dependency resolution first (layer caching)
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Copy application sources
COPY src/ src/

# Build the Boot jar without running tests
RUN ./gradlew --no-daemon bootJar

# ──────────────────────────────────────────────────────────────
# Stage 2: Lean runtime image
#
# Must match the toolchain major version from stage 1. The jar is
# emitted at class-file version 65, which only a Java 21+ runtime
# can load.
# ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# Install curl so Compose healthchecks can use:
#   curl -f http://localhost:8080/actuator/health
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

# The app container must reach a Docker daemon to launch Phase 4 sandbox containers.
# Mount the host socket at /var/run/docker.sock and this env var tells docker-java
# where to find it (DefaultDockerClientConfig picks it up automatically).
ENV DOCKER_HOST=unix:///var/run/docker.sock

EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]
