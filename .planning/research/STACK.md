# Technology Stack

**Project:** Collaborative Code Editor Backend
**Researched:** 2026-03-26
**Overall Confidence:** MEDIUM -- versions are based on training data (cutoff ~May 2025). Exact latest patch versions should be verified against Maven Central before initializing the project.

## Recommended Stack

### Language & Runtime

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| Java | 21 (LTS) | Runtime | Java 21 is the current LTS (released Sep 2023). Virtual threads (Project Loom, finalized in 21) are directly useful for handling many concurrent WebSocket connections. Pattern matching, record patterns, and sealed classes improve OT model code. | HIGH |
| Eclipse Temurin | 21 | JDK Distribution | Standard OpenJDK distribution. GraalVM only if native image becomes a goal later. | HIGH |

### Core Framework

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| Spring Boot | 3.3.x+ | Application framework | Spring Boot 3.x requires Java 17+ and Jakarta EE 9+. Use the latest stable 3.x release from start.spring.io at project init time. | MEDIUM |
| Spring Web (MVC) | (managed by Boot) | REST API | Session CRUD endpoints, auth endpoints. No need for WebFlux given OT's inherently sequential server-authoritative model. | HIGH |
| Spring WebSocket | (managed by Boot) | Real-time communication | STOMP-over-WebSocket provides structured message routing with topic subscriptions per session. | HIGH |
| Spring Security | (managed by Boot) | Authentication & authorization | Custom JWT filter for REST + WebSocket auth. | HIGH |
| Spring Data JPA | (managed by Boot) | Database access | Repository pattern fits session/user/operation CRUD. | HIGH |
| Spring Data Redis | (managed by Boot) | Redis integration | RedisTemplate, pub/sub listener containers, and Lettuce client. Covers caching, atomic counters, and cross-instance message relay. | HIGH |

### Database

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| PostgreSQL | 16.x | Primary database | Operation log benefits from PostgreSQL's efficient B-tree indexing on (session_id, revision). | MEDIUM |
| Flyway | 10.x (managed by Boot) | Schema migrations | Version-controlled SQL migrations. Requires `flyway-database-postgresql` module since Flyway 10. | HIGH |
| HikariCP | (managed by Boot) | Connection pool | Default connection pool in Spring Boot. | HIGH |

### Caching & Pub/Sub

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| Redis | 7.x | Cache + pub/sub + atomic counters | Pub/sub for cross-instance WebSocket relay, atomic INCR for revision counters, key-value cache for active session documents. | MEDIUM |
| Lettuce | (managed by Spring Data Redis) | Redis client | Default Redis client in Spring Boot. Non-blocking, supports pub/sub natively, thread-safe. Do NOT switch to Jedis. | HIGH |

### Code Execution

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| docker-java | 3.3.x | Docker API client | `docker-java-core` + `docker-java-transport-httpclient5` modules. Typed API for container creation, resource limits, streaming output. | MEDIUM |

### Authentication

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| jjwt (io.jsonwebtoken) | 0.12.x | JWT creation & validation | `jjwt-api`, `jjwt-impl`, `jjwt-jackson`. More ergonomic than Spring Security's built-in JWT support for custom self-issued flows. | MEDIUM |

### Testing

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| JUnit 5 | (managed by Boot) | Unit testing | OT edge case tests and convergence tests. | HIGH |
| Testcontainers | 1.19.x+ | Integration testing | Real PostgreSQL and Redis in Docker. Spring Boot 3.1+ has first-class support via `@ServiceConnection`. | MEDIUM |
| Mockito | (managed by Boot) | Mocking | Standard for unit testing service layers. | HIGH |
| AssertJ | (managed by Boot) | Assertions | Fluent assertion API, superior to JUnit's built-in assertions. | HIGH |

### Build Tool

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| Gradle (Kotlin DSL) | 8.x | Build system | Faster than Maven for incremental builds. Kotlin DSL provides type-safe build scripts with IDE autocompletion. | HIGH |

### Infrastructure

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| Docker | 24+ | Containerization | Multi-stage Dockerfile for app + code execution sandboxing. | HIGH |
| Docker Compose | v2 | Local orchestration | App + PostgreSQL + Redis with a single command. | HIGH |

### Supporting Libraries

| Library | Purpose | Confidence |
|---------|---------|------------|
| Lombok | Boilerplate reduction (`@Data`, `@Builder`, `@Slf4j`) | HIGH |
| SpringDoc OpenAPI 2.x | Swagger UI for REST endpoints, useful for portfolio README | MEDIUM |

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Reactive stack | Spring MVC + WebSocket | Spring WebFlux | OT is sequential. WebFlux adds complexity without benefit. Virtual threads handle concurrency. |
| Redis client | Lettuce | Jedis | Jedis requires connection pooling and is blocking. Lettuce is non-blocking and Spring Boot default. |
| JWT library | jjwt | Spring Security OAuth2 Resource Server | OAuth2 RS is for external IdP tokens. Self-issued JWTs are simpler with jjwt. |
| ORM | Spring Data JPA | jOOQ | Data model is straightforward CRUD + ordered inserts. JPA is sufficient. |
| WebSocket | STOMP over WebSocket | Raw WebSocket | STOMP provides routing, subscriptions, headers out of the box. |
| Testing | Testcontainers | H2 in-memory | H2 has subtle PostgreSQL incompatibilities. Testcontainers catches real issues. |
| Container API | docker-java | ProcessBuilder + docker CLI | Typed API, proper error handling, streaming output. CLI shelling is fragile. |

## What NOT to Use

| Technology | Why Not |
|------------|---------|
| Spring WebFlux / Project Reactor | OT is sequential. Virtual threads give concurrency without reactive ceremony. |
| Kafka / RabbitMQ | Overkill for 2-3 instances. Redis pub/sub is fire-and-forget, matching WebSocket relay. |
| MongoDB / NoSQL | Operation log is inherently ordered and relational. PostgreSQL handles revision consistency better. |
| Hazelcast / Embedded cache | Redis already required for pub/sub. One fewer technology. |
| gRPC | Monolithic backend communicating with browsers. No inter-service communication needed. |
| Any OT/CRDT library | OT engine from scratch is the point of the project. |

## Spring Boot Dependencies

```kotlin
// build.gradle.kts -- dependencies block

// Core
implementation("org.springframework.boot:spring-boot-starter-web")
implementation("org.springframework.boot:spring-boot-starter-websocket")
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation("org.springframework.boot:spring-boot-starter-validation")

// Database
runtimeOnly("org.postgresql:postgresql")
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")

// JWT
implementation("io.jsonwebtoken:jjwt-api:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

// Docker
implementation("com.github.docker-java:docker-java-core:3.3.6")
implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.6")

// API Documentation
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

// Dev tools
compileOnly("org.projectlombok:lombok")
annotationProcessor("org.projectlombok:lombok")
developmentOnly("org.springframework.boot:spring-boot-devtools")

// Testing
testImplementation("org.springframework.boot:spring-boot-starter-test")
testImplementation("org.springframework.security:spring-security-test")
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.testcontainers:postgresql")
```

**NOTE:** Verify exact patch versions of jjwt, docker-java, and springdoc against Maven Central before project init.

## Java 21 Features to Leverage

| Feature | Where to Use | Benefit |
|---------|-------------|---------|
| Virtual Threads | WebSocket handling, Docker execution | `spring.threads.virtual.enabled=true` — no OS thread pool exhaustion |
| Records | OT operations, DTOs, WebSocket messages | Immutable, compact data carriers |
| Sealed interfaces | Operation type hierarchy | `sealed interface Operation permits InsertOp, DeleteOp` — exhaustive matching |
| Pattern matching (switch) | OT transform dispatch | `case InsertOp i -> transformInsertInsert(i, other)` |
| Text blocks | SQL migrations, test fixtures | Multi-line strings |

## Key Configuration (application.yml)

```yaml
spring:
  threads:
    virtual:
      enabled: true

  datasource:
    url: jdbc:postgresql://localhost:5432/collab_editor
    hikari:
      maximum-pool-size: 20

  data:
    redis:
      host: localhost
      port: 6379

  jpa:
    hibernate:
      ddl-auto: validate  # Flyway manages schema
    open-in-view: false    # Disable OSIV

  flyway:
    locations: classpath:db/migration
```
