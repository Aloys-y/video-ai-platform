# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build all modules
mvn clean install -DskipTests

# Start API service (port 8080, context-path /api)
cd video-api && mvn spring-boot:run

# Start Worker service (port 8081)
cd video-worker && mvn spring-boot:run

# Start infrastructure services (MySQL 13306, Redis 16379, Kafka 19092/19093, MinIO 9000/9001)
cd docker && docker-compose up -d
```

No tests exist yet. Test framework: JUnit 5 + Mockito via `spring-boot-starter-test`.

## Project Architecture

Multi-module Maven project (Java 17, Spring Boot 3.2.4) for AI video content analysis.

### Module Dependency Chain

```
video-common  <--  video-infrastructure  <--  video-api (REST, port 8080)
                                            <--  video-worker (async, port 8081)
```

- **video-common**: Domain entities (`User`, `UploadSession`, `AnalysisTask`, `UserQuota`), DTOs, enums (`ErrorCode`, `TaskStatus` with state machine), `BusinessException`, `IdGenerator`, Kafka message types
- **video-infrastructure**: MyBatis-Plus config/mappers, Redisson client, Kafka topic constants (`TopicConstant`), MinIO `StorageService`, Redis key definitions
- **video-api**: Controllers (`Auth`, `Upload`), services, `AuthInterceptor` (JWT + API Key dual auth), `RateLimitInterceptor` (3-tier: Guava global -> Redis per-user -> per-endpoint), `GlobalExceptionHandler`, SpringDoc OpenAPI config
- **video-worker**: Currently only the application class with `@EnableAsync`. Worker consumers and processors are not yet implemented.

### Key Design Patterns

- **Unified response**: All controllers return `ApiResponse<T>` with structured `ErrorCode` (hierarchical: 1xxxx system, 2xxxx upload, 3xxxx third-party, 4xxxx rate-limiting)
- **Dual authentication**: JWT Bearer (priority) and API Key (`X-API-Key` header), implemented as `HandlerInterceptor`
- **Distributed locking**: Redisson `RLock` with double-check pattern for chunk upload dedup
- **ThreadLocal user context**: `UserContext` set by interceptor, cleaned up in `afterCompletion`
- **State machine**: `TaskStatus.canTransitionTo()` controls valid task state transitions
- **Chunk upload with instant upload**: File hash-based dedup ("秒传"), MinIO Compose API for server-side merge

### Configuration Profiles

- Default: `application.yml` in each module (ports, framework settings, rate limits)
- Dev: `application-dev.yml` (MySQL/Redis/Kafka/MinIO connection details, Druid pool, AI config)
- Run with: `mvn spring-boot:run -Dspring-boot.run.profiles=dev`

### Swagger UI

Access at `http://localhost:8080/swagger-ui.html`. Swagger paths are excluded from auth in `WebMvcConfig`.

## Git Workflow

Feature branch development with PR merges to `main`. Never push directly to `main`.

## Database

5 tables defined in `sql/schema.sql`: `user`, `upload_session`, `analysis_task`, `user_quota`, `ai_call_log`. Auto-initialized by Docker Compose MySQL container.

## Development Language

All user communication in Chinese. Code comments and commit messages in Chinese or English.
