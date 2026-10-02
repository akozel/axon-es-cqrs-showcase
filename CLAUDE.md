# axon-es-cqrs-showcase

CQRS / Event Sourcing showcase built on Axon Framework 5. Modules:

- `account-verification-service/` — plain Java + Gradle (no Spring Boot), PostgreSQL event store.

## Rules

- **Axon Framework 5 only** (`org.axonframework`, Apache 2.0, line 5.2.x). Do **not** use or suggest any
  Axoniq Framework component (`io.axoniq.framework:*`), e.g. `axoniq-postgresql`, `axon-server-connector`,
  `axoniq-distributed-messaging`, `axoniq-event-streaming`, `axoniq-dead-letter`, `axoniq-message-transformation`,
  `axoniq-framework-bom`. If a feature only exists there, say so and propose an open-source alternative.
- Dependency management goes through `org.axonframework:axon-framework-bom` (never `axoniq-framework-bom`).
- For any Axon work, use the `axoniq-app-development:axoniq-app-dev` skill and read the relevant guide before
  writing code. The skill also documents Axoniq Framework — ignore those parts. Javadoc: https://apidocs.axoniq.io/5.2/
- No Spring / Spring Boot: Axon is configured in plain Java (`EventSourcingConfigurer`).
- Event store is PostgreSQL via the open-source `AggregateBasedJpaEventStorageEngine` (JPA/Hibernate). It supports
  exactly one tag per event: key = aggregate type, value = aggregate id (e.g. `Account` → SSN).
- One unit of work = one transaction (`JpaUnitOfWorkTransactionManager`), opened at its first write. Until it commits,
  nothing else on that thread may open a connection; the manager fails fast. So subscribing event handlers only write
  through the unit of work's `EntityManager`; handlers that send commands or read the event store go in pooled
  streaming processors.
- Local infrastructure runs on Podman (`podman compose`). Configuration lives in `.env` (git-ignored); keep
  `.env.example` in sync when adding variables. Tests don't read `.env`.

## Commands

Run from `account-verification-service/`:

- `cp .env.example .env && podman compose up -d` — start PostgreSQL for `./gradlew run` and manual testing
- `./gradlew build` — compile and test. The PostgreSQL integration tests start their own PostgreSQL with
  Testcontainers: Podman through its Docker-compatible socket (`/var/run/docker.sock` from `podman-mac-helper`, or
  `DOCKER_HOST`). They are skipped when no container runtime is reachable.
- `./gradlew run` — start the application (reads `.env`)
