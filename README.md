# axon-es-cqrs-showcase

CQRS / Event Sourcing showcase on Axon Framework 5 (open source, 5.2.x) with a PostgreSQL event store. Plain Java,
no Spring.

## Requirements

- **JDK 25 (mandatory).** The build pins Java 25 with a Gradle toolchain and fails when no JDK 25 is installed. Older
  JDKs are not supported: commands run on virtual threads, and those need JDK 24+ (JEP 491). On JDK 21 they pin their
  carrier threads while waiting for a pooled connection, and the service deadlocks under load.
- **Podman** (or Docker). `podman compose` runs the local PostgreSQL. The integration tests start their own
  PostgreSQL with Testcontainers through the Docker-compatible socket (`/var/run/docker.sock` from
  `podman-mac-helper`, or `DOCKER_HOST`); without a container runtime they are skipped.

The Gradle wrapper (`./gradlew`, in the repository root) downloads Gradle itself.

## Quick start

```shell
(cd account-verification-service && cp .env.example .env && podman compose up -d)   # PostgreSQL for ./gradlew run
./gradlew build                                                                     # compile and test all modules
./gradlew run                                                                       # start the service (reads its .env)
```

## Architecture

One Gradle build with two modules:

- **`axon-foundation`** (`by.akozel.axon.foundation`): the base configuration of Axon Framework 5 that every service
  builds on. It knows no service. `persistence` holds the PostgreSQL pool, the unit-of-work transactions and the JPA
  event store; `messaging` holds command execution on virtual threads with an admission limit. The two don't depend on
  each other. Its test fixtures (`PostgresContainer`, `RequiresPostgresContainer`, `PostgresAxonEnvironment`) let a
  service's tests run against PostgreSQL with the real wiring.
- **`account-verification-service`** (`by.akozel.accountverification`), split into layers:

  | Layer          | Package               | Contents                                                                    |
  |----------------|-----------------------|-----------------------------------------------------------------------------|
  | domain         | `domain.account`      | event-sourced entities with their commands, events and rules (`Account`)    |
  | application    | `application.account` | registers the entities and handlers with Axon (`AccountConfiguration`)      |
  | infrastructure | `infrastructure`      | service-specific adapters (read model storage, …); none yet                 |
  | presentation   | `presentation`        | entry points (HTTP, CLI, …); none yet                                       |

`Application` in the service's root package is the entry point and composition root: it reads the settings and wires
`axon-foundation` and the layers together. Dependencies point inward only: domain ← application ← infrastructure /
presentation ← `Application`; only `Application` (and the service's own infrastructure) use `axon-foundation`. The
domain keeps Axon's modelling annotations, but neither it nor the application layer may use JPA, Hibernate, JDBC or
the connection pool. `LayeredArchitectureTest` checks the service, `FoundationArchitectureTest` the module.

The namespace of a message (`@Event`/`@Command(namespace = …)`) is part of its stored type name, not the Java
package: it stays `by.akozel.accountverification.account` so that events already written can still be read.

## Test coverage

Every module measures how much of its main code its own tests run, with JaCoCo. `./gradlew test` writes the report
to `<module>/build/reports/jacoco/test/html/index.html`: totals per package and class, and the sources with covered
lines in green, partly covered branches in yellow and code never run in red. `./gradlew check` (part of
`./gradlew build`) fails when instruction, branch or line coverage is below **95%**, for the module and for each class.
The entry point `Application` is the only class left out. The PostgreSQL tests count too, so a build without a
container runtime fails the check.

## Continuous integration

`.github/workflows/ci.yml` runs on every pull request and every push to `main`, on Ubuntu with JDK 25 and the
runner's Docker for Testcontainers. It is a pipeline of separate jobs, each working on what the previous one produced:

| Stage | Runs | Hands on |
|---|---|---|
| **Build** | `./gradlew assemble testClasses` | the compiled classes |
| **Test** | `./gradlew test` on those classes, without compiling | JaCoCo execution data; test results attached to the run |
| **Coverage** | `jacocoTestReport`, then `jacocoTestCoverageVerification` (95%), without testing again | the HTML report, attached to the run as `coverage-report` |
| **Publish** | only for `main`: deploys the report to GitHub Pages | |

A failed stage stops the ones after it. The coverage stage's summary shows the coverage per module.

For `main` the report is also published on GitHub Pages: **https://akozel.github.io/axon-es-cqrs-showcase/**, an
index of the modules linking to their reports. It is published even when the 95% check fails, so the missing code
can be seen. One-time setup: *Settings → Pages → Build and deployment → Source: GitHub Actions*.

## Configuration

`.env` (git-ignored, copied from `.env.example`) is read by `compose.yaml` and by `./gradlew run`. Tests don't read it.

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT` | `localhost`, `5432` | Where PostgreSQL runs |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | required | Database and credentials |
| `POSTGRES_LOCK_TIMEOUT` | `5s` | How long a statement may wait for a lock |
| `POSTGRES_IDLE_IN_TRANSACTION_TIMEOUT` | `60s` | How long a transaction may stay idle before PostgreSQL ends the session |
| `COMMAND_CONCURRENCY_PER_CPU` | `1000` | Commands that may execute at once per CPU available to the JVM; the limit is this value × CPUs |

## Threading model

- Database access is blocking JDBC (PgJDBC and HikariCP) on virtual threads; there are no reactive drivers.
- A command sent from outside a handler (an entry point) runs on a virtual thread: the caller's own if it is one,
  otherwise a new one. At most `COMMAND_CONCURRENCY_PER_CPU` × CPUs such commands execute at once; one more is
  rejected immediately with `CommandConcurrencyLimitExceededException`, a transient failure: send it again later.
- A command sent from inside a handler, with the handler's `ProcessingContext`, runs on the sender's thread as part
  of the command that sent it, and doesn't count against the limit.
- One unit of work is one database transaction on one thread. The transaction starts at the first write, so a
  connection is held only from then until the commit. The connection pool, not the number of threads, limits how many
  commands work with the database at the same time.
- The state of a unit of work lives in its `ProcessingContext`, never in a `ThreadLocal`; `ArchitectureTest` and
  `FoundationArchitectureTest` check this.

## Sending commands from handlers

Send a command from inside a handler only with the handler's `ProcessingContext`: inject a `CommandDispatcher`, or use
`CommandDispatcher.forContext(context)` or `CommandGateway.send(command, context)`. The new command then carries the
sender's correlation data, and the transaction check below can see where it comes from. `ArchitectureTest` enforces
this for the handlers of the production code; it cannot see commands sent through helper classes.

A subscribing event handler runs inside the publishing unit of work after its events were written, so that unit of
work already holds its transaction. A command sent from there with the context is refused at once, from whichever
thread it is sent: handling it would need a second connection, which can exhaust the pool or wait forever for the
first transaction's locks. Such a handler belongs in a pooled streaming processor.

## Caveats

- **A refusal reaches the sender only through the command's result.** A handler that sends a command and ignores the
  result (`dispatcher.send(command)` without waiting for it) never learns that it was refused: the outer command
  commits, the nested one never runs, and only a WARN line ("Refused command …") is logged. Return the result or wait
  for it.
- **A command sent without the context is not checked.** Sent from a subscribing handler, it fails only when the
  database or the pool gives up: after `POSTGRES_LOCK_TIMEOUT` (5 s) when it writes to the same aggregate, after the
  pool's connection timeout (30 s) when it needs a connection the exhausted pool cannot give. The rule above avoids
  this.
- **Don't send a command with the routing key of the command being handled**, i.e. to the handler's own aggregate.
  Axon runs commands with the same routing key one at a time (`CommandSequencingInterceptor`, on by default). Sent
  without the context, such a command waits for its sender to finish while the sender waits in `send()`, which is
  synchronous here: both hang for good, and as neither holds a database connection yet, no timeout ends it. Sent with
  the context it doesn't hang in Axon 5.2.3, but only because Axon drops the routing key of a command sent with a
  context (the correlation data interceptor rebuilds the message without it). Don't rely on that; check again after
  upgrading Axon.

The rules for working in this repository are in [CLAUDE.md](CLAUDE.md).
