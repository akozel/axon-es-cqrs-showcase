package by.akozel.axon.foundation.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import by.akozel.axon.foundation.testing.PostgresContainer;
import by.akozel.axon.foundation.testing.RequiresPostgresContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Verifies the connection {@link PostgresDatabase} sets up with its defaults. Runs against the test container. */
@RequiresPostgresContainer
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PostgresDatabasePostgresTest {

    @Test
    void boundsLockWaitsAndIdleTransactionsOfEverySession() {
        try (PostgresDatabase database = PostgresDatabase.connect(PostgresContainer.settings())) {
            // when
            EntityManager entityManager = database.entityManagerFactory().createEntityManager();
            try {
                Object lockTimeout = entityManager.createNativeQuery("show lock_timeout").getSingleResult();
                Object idleTimeout = entityManager.createNativeQuery("show idle_in_transaction_session_timeout")
                                                  .getSingleResult();

                // then
                assertThat(lockTimeout).isEqualTo("5s");
                assertThat(idleTimeout).isEqualTo("1min");
            } finally {
                entityManager.close();
            }
        }
    }

    @Test
    void closesTheEntityManagerFactoryOnClose() {
        // given
        PostgresDatabase database = PostgresDatabase.connect(PostgresContainer.settings());
        EntityManagerFactory entityManagerFactory = database.entityManagerFactory();

        // when
        database.close();

        // then
        assertThat(entityManagerFactory.isOpen()).isFalse();
    }
}
