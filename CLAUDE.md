# axon-es-cqrs-showcase

CQRS / Event Sourcing showcase built on Axon Framework 5. One Gradle build (wrapper, `settings.gradle.kts`, version
catalog `gradle/libs.versions.toml` and the `java-conventions` plugin in `build-logic/` live in the repository root)
with the modules:

- `axon-foundation/` — base configuration of Axon shared by the services (`by.akozel.axon.foundation`): `persistence`
  (PostgreSQL pool, unit-of-work transactions, JPA event store) and `messaging` (command execution on virtual threads).
  Knows no service; its test fixtures (`…foundation.testing`) give services a PostgreSQL test environment.
- `account-verification-service/` — plain Java 25 (no Spring Boot), depends on `axon-foundation`.

## Rules

- **Layers** of a service (packages of `by.akozel.accountverification`): `domain` (entities, their commands and events;
  Axon annotations allowed) ← `application` (registers them with Axon) ← `infrastructure` (service-specific adapters,
  none yet) and `presentation` (entry points, none yet) ← `Application` in the root package (composition root).
  Only `Application` and the service's infrastructure use `axon-foundation`; domain and application never use
  JPA/Hibernate/JDBC/Hikari. Generic Axon infrastructure goes in `axon-foundation`, never anything about a service; its
  `persistence` and `messaging` don't depend on each other. `LayeredArchitectureTest` and `FoundationArchitectureTest`
  check it. Keep message namespaces (`by.akozel.accountverification.account`) unchanged when moving classes: they are
  stored event type names.
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
- **Java 25 is mandatory** (Gradle toolchain in `build.gradle.kts`, requirement in README). Don't lower it: virtual
  threads need JDK 24+ (JEP 491); on JDK 21 they deadlock waiting for pooled connections inside Axon's
  `synchronized` stream code.
- **Threading model: virtual threads + blocking JDBC.** Don't introduce reactive or event-loop database access (R2DBC,
  Vert.x SQL client, Hibernate Reactive): Axon 5.2 joins futures inside the unit of work and would block the event
  loop. Blocking calls are fine on virtual threads.
- Entry points send commands without a `ProcessingContext`. `VirtualThreadCommandBus` runs each on a virtual thread
  and admits at most `COMMAND_CONCURRENCY_PER_CPU` × available CPUs at once; one more is rejected immediately
  (`CommandConcurrencyLimitExceededException`). Dispatch interceptors run on the command's virtual thread, so they
  don't see the caller's ThreadLocals (e.g. MDC).
- **Inside handlers, send commands only with the handler's `ProcessingContext`**: `CommandDispatcher` (handler
  parameter or `CommandDispatcher.forContext(context)`) or `CommandGateway.send/sendAndWait(…, context)`; never the
  `CommandBus` or a gateway call without the context. Such a command runs on the sender's thread and doesn't count
  against the limit. Return or join its result: a refusal reaches the sender only through it. `ArchitectureTest`
  enforces this for production code; it cannot see sends made through helper classes. Never send a command with the
  routing key of the command being handled (see README, Caveats).
- One unit of work = one transaction (`JpaUnitOfWorkTransactionManager`), opened at its first write. Until it commits,
  nothing it waits for may need a second connection. `commandDispatchInterceptor()` refuses a command sent with the
  context of such a unit of work, from any thread. A command sent without the context and an event store read are not
  checked at runtime; only the database's `lock_timeout` and the pool's connection timeout end them. So subscribing
  event handlers only write through the unit of work's `EntityManager`; handlers that send commands or read the event
  store go in pooled streaming processors.
- **No `ThreadLocal`** in production code: keep the state of a unit of work in `ProcessingContext` resources.
  `ArchitectureTest` (service) and `FoundationArchitectureTest` (module) check it.
- **After a refactoring, run the existing tests**: `./gradlew build` with a container runtime reachable, and check
  that no PostgreSQL test was skipped. Existing tests may only get minimal edits (wiring, a renamed API, a message
  text); name each edit and its reason in the change summary. Cover new behaviour with new tests instead of rewriting
  existing ones.
- **Test coverage ≥ 95%** (JaCoCo, configured in `java-conventions`, version in the catalog). `check` (so
  `./gradlew build`) fails when **INSTRUCTION, BRANCH or LINE** coverage is below 95%, for a module as a whole *and*
  for each of its classes. For a class with fewer than 20 branches or lines, that means none may be missed. Only a
  module's own tests count: code of `axon-foundation` that the service's tests run doesn't. METHOD, CLASS and
  COMPLEXITY are shown in the report but not enforced. Never lower the threshold, drop a counter or exclude a class to
  get the build green: write the missing test. A branch no test can reach (redundant defensive code) is removed, not
  excluded. Exclusions go only through `coverage { excludedClasses }` in the module's build script, one class at a time
  with the reason; today only `Application` (reads the real environment, then blocks forever). Without a container
  runtime the PostgreSQL tests are skipped and the coverage check fails. That is intended: it is not a green build.
- **CI** (`.github/workflows/ci.yml`, GitHub Actions, Ubuntu with Docker) is a pipeline of separate jobs: build
  (`assemble testClasses`, hands on `*/build/classes/java/`) → test (`test` on those classes with the compile tasks
  excluded, hands on `*/build/jacoco/test.exec`) → coverage (`jacocoTestReport`, `jacocoTestCoverageVerification`
  with `test` and `compileJava` excluded) → pages (`main` only). The coverage report goes to the run as an artifact
  and, for `main`, to GitHub Pages (https://akozel.github.io/axon-es-cqrs-showcase/), collected by
  `.github/scripts/coverage_site.py`, which finds every module's report on its own. A new source set or verification
  task needs the matching change there: its `compile…Java` task in the test stage's `-x` list, a new `check`
  task in a stage, since CI doesn't run `check` as a whole. Keep its JDK in step with the toolchain. Don't let CI skip
  tests or the coverage check.
- Local infrastructure runs on Podman (`podman compose`). Configuration lives in `.env` (git-ignored); keep
  `.env.example` in sync when adding variables. Tests don't read `.env`.

## Commands

Run Gradle from the repository root (JDK 25 must be installed):

- `cd account-verification-service && cp .env.example .env && podman compose up -d` — start PostgreSQL for
  `./gradlew run` and manual testing (`compose.yaml`, `.env` live in the service's directory)
- `./gradlew build` — compile and test all modules. The PostgreSQL integration tests start their own PostgreSQL with
  Testcontainers: Podman through its Docker-compatible socket (`/var/run/docker.sock` from `podman-mac-helper`, or
  `DOCKER_HOST`). They are skipped when no container runtime is reachable, and then the coverage check fails.
- `./gradlew test` — run the tests and write the coverage report: `<module>/build/reports/jacoco/test/html/index.html`
  (HTML with the sources highlighted line by line; XML next to it). `./gradlew check` also verifies the 95% minimum.
- `./gradlew run` — start the service (reads `account-verification-service/.env`)
