plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.collabeditor"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
    val activeSpringProfile = System.getProperty("spring.profiles.active")
        ?: System.getenv("SPRING_PROFILES_ACTIVE")
        ?: "test"
    systemProperty("spring.profiles.active", activeSpringProfile)
    // Forward Docker host for Testcontainers (supports Colima, Podman, etc.)
    val dockerHost = System.getenv("DOCKER_HOST")
    val dockerSocketOverride = System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")
    if (!dockerHost.isNullOrBlank()) {
        environment("DOCKER_HOST", dockerHost)
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", dockerSocketOverride ?: "/var/run/docker.sock")
    }
    // Ensure user.home is set so Testcontainers can find ~/.testcontainers.properties
    systemProperty("user.home", System.getProperty("user.home"))
    // Set Docker API version for docker-java compatibility with Docker 25+ (min API 1.44)
    environment("DOCKER_API_VERSION", "1.44")
    jvmArgs("-DDOCKER_API_VERSION=1.44")
}
