# ──────────────────────────────────────────────────────────────
# Stage 1: Build the Spring Boot jar using the Gradle wrapper
# ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS build

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
# ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

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
