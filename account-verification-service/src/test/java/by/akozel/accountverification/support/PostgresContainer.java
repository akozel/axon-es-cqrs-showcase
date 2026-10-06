package by.akozel.accountverification.support;

import java.util.Map;

import by.akozel.accountverification.infrastructure.persistence.DatabaseSettings;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL the integration tests run against: one container per test run, started on first use and removed when
 * the JVM exits. Same image as compose.yaml, which stays for {@code ./gradlew run} and manual testing.
 * <p>
 * Testcontainers needs a Docker-compatible API. With Podman on macOS, {@code podman-mac-helper} provides it at
 * {@code /var/run/docker.sock}; otherwise point {@code DOCKER_HOST} at the Podman machine's API socket.
 */
public final class PostgresContainer {

    private static final DockerImageName IMAGE = DockerImageName.parse("docker.io/library/postgres:17-alpine")
                                                                .asCompatibleSubstituteFor("postgres");

    private PostgresContainer() {}

    /** Whether Testcontainers can reach a container runtime; see {@link RequiresPostgresContainer}. */
    public static boolean available() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /** Settings to connect to the container, which is started on the first call. */
    public static DatabaseSettings settings() {
        PostgreSQLContainer container = Started.CONTAINER;
        return DatabaseSettings.fromEnvironment(Map.of(
                "POSTGRES_HOST", container.getHost(),
                "POSTGRES_PORT", String.valueOf(container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                "POSTGRES_DB", container.getDatabaseName(),
                "POSTGRES_USER", container.getUsername(),
                "POSTGRES_PASSWORD", container.getPassword()));
    }

    /** Holder idiom: the container starts once, when first needed. */
    private static final class Started {

        static final PostgreSQLContainer CONTAINER = start();

        private static PostgreSQLContainer start() {
            PostgreSQLContainer container = new PostgreSQLContainer(IMAGE);
            container.start();
            return container;
        }
    }
}
