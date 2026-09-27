# Instantly Agents Architecture Guide

## Project direction

- Build the first release as a modular monolith that can later be split into microservices.
- Use Spring Boot with PostgreSQL and Liquibase.
- Keep each bounded context in its own build module and implement hexagonal architecture inside every domain module.
- Start small: create domain modules only when they are needed instead of scaffolding every possible module in advance.

## Current modules

- `bootstrap/`: executable Spring Boot composition root, runtime configuration, health and lifecycle.
- `modules/agent/`: characters, conversation memory, durable reply jobs and external client adapters.
- Add further bounded contexts only when needed. No empty BFF or shared kernel.
- Java 26, Spring Boot 4.1, Maven, PostgreSQL, Liquibase. No Redis requirement in v1.
- Never access Instantly Backend tables. Integrate through its authenticated HTTP/WebSocket contracts.
- Do not log message text, model prompts, passwords, tokens or private keys.
- Preserve encrypted input and durable work before acknowledging backend delivery.
- Reuse persisted outbound frames on retry; never regenerate ciphertext for an uncertain send.

## Module architecture

Each domain module owns its hexagon:

```text
<module>/
  domain/
    model/
    service/
    event/
    exception/
  application/
    port/in/
    port/out/
    usecase/
  adapter/
    in/internal/
    out/persistence/
    out/messaging/
    out/cache/
    out/external/
```

- `domain` contains business rules and has no framework or infrastructure dependency.
- `application.port.in` exposes use cases. The BFF and other modules call these ports, not domain services directly.
- `application.port.out` defines infrastructure and integration needs.
- `application.usecase` implements inbound ports and coordinates the domain.
- Adapters implement inbound or outbound integration concerns.

## Dependency rules

```text
BFF -> application port.in -> application use case -> domain
adapter.out -> application port.out
bootstrap -> BFF and module wiring
modules -> shared-kernel (only when genuinely necessary)
```

- Domain modules must not depend on `bff` or `bootstrap`.
- A module must not access another module's entity, repository, persistence adapter, or tables.
- Synchronous cross-module communication goes through the target module's public inbound port.
- Asynchronous communication uses explicit domain/application event contracts.
- Pass identifiers or explicit contracts across module boundaries, not ORM-managed entities.
- Avoid cross-module ORM relationships, foreign keys, and transactions that would prevent later service extraction.

## BFF

- Treat the BFF as a thin presentation and orchestration module, not as a domain module.
- It owns HTTP endpoints, request/response DTOs, validation, DTO mapping, authentication context, security integration, and cross-use-case orchestration.
- It must not contain business rules or access persistence adapters directly.

## Bootstrap

- `bootstrap` is the composition root and the only executable application module.
- It owns the Spring Boot entry point, dependency injection/wiring, runtime configuration, infrastructure startup, observability, health checks, and application lifecycle.
- It may wire database, Redis, messaging, scheduling, migrations, BFF, and module adapters, but must not contain business rules.
- Domain modules must not know about bootstrap.

## Shared kernel

- Keep `shared-kernel` very small and framework-independent.
- Add a type only when its meaning and behavior are genuinely identical and stable across bounded contexts.
- Suitable candidates include minimal identifier/value types, domain event primitives, a clock abstraction, and stable pagination contracts.
- Do not place domain entities, repositories, use cases, HTTP DTOs, persistence annotations, module-specific exceptions, or miscellaneous utilities here.
- Prefer temporary duplication over premature coupling. Promote a concept only after shared semantics are proven.

## Data and infrastructure

- PostgreSQL is the source of truth; Liquibase owns schema changes.
- Store conversation text, summaries and job payloads as plain `TEXT`; do not add application-level database encryption.
- End-to-end wire encryption and encryption of private identity files are separate from database storage.
- A shared PostgreSQL instance is acceptable initially, but each domain module owns its schema/tables and migrations.
- Redis is not a source of truth. Use it for caching, rate limiting, short-lived tokens, sessions, and other ephemeral state.
- Namespace Redis keys by module, for example `feed:timeline:{userId}`.
- Design reliable event publication so an outbox pattern can be introduced without changing domain boundaries.

## Testing and enforcement

- Unit-test domain rules without Spring.
- Test application use cases against port fakes.
- Use integration tests for adapters with Testcontainers-backed PostGIS/PostgreSQL and Redis where appropriate.
- Add architecture tests (for example ArchUnit and/or Spring Modulith verification) to enforce package and module dependency rules.
- Keep transport DTOs, persistence models, and domain models separate, with explicit mapping at boundaries.

## Lombok conventions

- Lombok is available to every Maven module through the root build and must remain an optional, compile-time-only dependency and an explicit annotation processor.
- Use `@RequiredArgsConstructor` for constructor injection and other constructors that only assign `final` dependencies.
- Keep constructors explicit when they validate, normalize, transform, or initialize values, or when constructor parameters need annotations such as Spring's `@Value`.
- Use `@NonNull` on Lombok-managed constructor fields only when fail-fast null validation is part of the class contract.
- Prefer Java records for immutable commands, results, value objects, and transport DTOs; do not replace records with Lombok classes.
- Do not use `@Data` or class-level `@Value` on domain models or JPA entities. Keep mutability, equality, and persistence lifecycle behavior explicit.
- Use focused annotations such as `@Getter`, `@Setter`, `@Builder`, and `@Slf4j` only when the generated API is intentional; avoid annotations that generate unused methods.
