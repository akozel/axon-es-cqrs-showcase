package by.akozel.axon.foundation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

/** Verifies what {@link PostgresDatabase} leaves behind when it cannot connect; needs no database. */
class PostgresDatabaseTest {

    @Test
    void closesThePoolWhenTheEventStoreCannotBeSetUp() throws Exception {
        // given: nothing listens on the port, and the pool starts without waiting for a first connection
        DatabaseSettings unreachable = DatabaseSettings.fromEnvironment(Map.of(
                "POSTGRES_PORT", String.valueOf(freePort()),
                "POSTGRES_DB", "event_store", "POSTGRES_USER", "axon", "POSTGRES_PASSWORD", "secret"));
        List<Thread> poolThreads = new CopyOnWriteArrayList<>();

        // when / then: Hibernate fails as it cannot read the database's metadata
        assertThatThrownBy(() -> PostgresDatabase.connect(unreachable, pool -> {
            pool.setInitializationFailTimeout(-1);
            pool.setConnectionTimeout(250);
            pool.setThreadFactory(task -> {
                Thread thread = new Thread(task, "pool-of-unreachable-database");
                thread.setDaemon(true);
                poolThreads.add(thread);
                return thread;
            });
        })).isInstanceOf(RuntimeException.class);

        // then: the pool was started, and closed again
        assertThat(poolThreads).isNotEmpty();
        for (Thread thread : poolThreads) {
            assertThat(thread.join(Duration.ofSeconds(10))).as("%s ended", thread).isTrue();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
