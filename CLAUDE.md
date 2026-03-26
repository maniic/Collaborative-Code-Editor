<!-- GSD:project-start source:PROJECT.md -->
## Project

**Collaborative Code Editor**

A real-time collaborative code editor backend built in Java with Spring Boot 3. Multiple users join coding sessions via WebSocket, edit the same document simultaneously with conflict-free resolution using Operational Transform, and execute code together in Docker-sandboxed containers. Designed as a portfolio piece showcasing distributed systems, real-time algorithms, and containerized execution — with a full frontend planned for a future milestone.

**Core Value:** The OT engine must guarantee document convergence — when multiple users edit simultaneously, every participant ends up with the same document, every time, with zero conflicts.

### Constraints

- **Tech stack**: Java 17+, Spring Boot 3, PostgreSQL, Redis, Docker — no negotiation
- **OT implementation**: From scratch, no OT libraries — this is the intellectual centerpiece
- **Execution sandbox**: Docker containers only — no WASM, no in-process execution
- **Scale target**: 2-3 server instances (prove architecture, not stress-test)
- **Package structure**: auth, session, websocket, ot, execution, redis, snapshot packages
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

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
## Java 21 Features to Leverage
| Feature | Where to Use | Benefit |
|---------|-------------|---------|
| Virtual Threads | WebSocket handling, Docker execution | `spring.threads.virtual.enabled=true` — no OS thread pool exhaustion |
| Records | OT operations, DTOs, WebSocket messages | Immutable, compact data carriers |
| Sealed interfaces | Operation type hierarchy | `sealed interface Operation permits InsertOp, DeleteOp` — exhaustive matching |
| Pattern matching (switch) | OT transform dispatch | `case InsertOp i -> transformInsertInsert(i, other)` |
| Text blocks | SQL migrations, test fixtures | Multi-line strings |
## Key Configuration (application.yml)
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
