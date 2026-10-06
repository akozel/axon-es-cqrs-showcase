package by.akozel.axon.foundation.testing;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.management.JMException;
import javax.management.ObjectName;

import by.akozel.axon.foundation.messaging.CommandExecutionConfiguration;
import by.akozel.axon.foundation.messaging.CommandExecutionSettings;
import by.akozel.axon.foundation.persistence.JpaEventStoreConfiguration;
import by.akozel.axon.foundation.persistence.PostgresDatabase;
import com.zaxxer.hikari.HikariConfig;
import jakarta.persistence.EntityManager;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

/**
 * The real wiring of this module (JPA event store on PostgreSQL + command execution on virtual threads) plus helpers
 * to inspect the database from the outside. Runs against the PostgreSQL test container, see
 * {@link PostgresContainer}. Registers no entities or handlers of its own; pass them to
 * {@link #start(Consumer, Consumer)}.
 */
public final class PostgresAxonEnvironment implements AutoCloseable {

    private final PostgresDatabase database;
    private final AxonConfiguration axon;
    private final String poolName;

    private PostgresAxonEnvironment(PostgresDatabase database, AxonConfiguration axon, String poolName) {
        this.database = database;
        this.axon = axon;
        this.poolName = poolName;
    }

    public static PostgresAxonEnvironment start() {
        return start(pool -> {});
    }

    public static PostgresAxonEnvironment start(Consumer<HikariConfig> poolCustomizer) {
        return start(poolCustomizer, configurer -> {});
    }

    /**
     * @param extraConfiguration registers the entities, handlers or processors under test, e.g. a service's own
     *                           configuration
     */
    public static PostgresAxonEnvironment start(Consumer<HikariConfig> poolCustomizer,
                                                Consumer<EventSourcingConfigurer> extraConfiguration) {
        String poolName = "test-" + UUID.randomUUID();
        PostgresDatabase database = PostgresDatabase.connect(PostgresContainer.settings(), pool -> {
            pool.setPoolName(poolName);
            pool.setRegisterMbeans(true); // exposes the statistics read by activeConnections()
            poolCustomizer.accept(pool);
        });
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        JpaEventStoreConfiguration.configure(configurer, database.entityManagerFactory());
        CommandExecutionConfiguration.configure(configurer, CommandExecutionSettings.defaults());
        extraConfiguration.accept(configurer);
        return new PostgresAxonEnvironment(database, configurer.start(), poolName);
    }

    public CommandGateway commands() {
        return axon.getComponent(CommandGateway.class);
    }

    public EventStore eventStore() {
        return axon.getComponent(EventStore.class);
    }

    public UnitOfWorkFactory unitsOfWork() {
        return axon.getComponent(UnitOfWorkFactory.class);
    }

    /**
     * Runs {@code body} in a unit of work created by the application's own factory, i.e. with
     * {@code JpaUnitOfWorkTransactionManager} attached. The future fails if the body or any phase fails.
     */
    public <R> CompletableFuture<R> unitOfWork(Function<ProcessingContext, R> body) {
        return unitsOfWork().create()
                            .executeWithResult(context -> CompletableFuture.completedFuture(body.apply(context)));
    }

    /** Runs a read against the database on a fresh {@link EntityManager}, completely outside any unit of work. */
    public <T> T query(Function<EntityManager, T> query) {
        EntityManager entityManager = database.entityManagerFactory().createEntityManager();
        try {
            return query.apply(entityManager);
        } finally {
            entityManager.close();
        }
    }

    public long countEventsOfAggregate(String aggregateIdentifier) {
        return query(em -> em.createQuery(
                                     "SELECT count(e) FROM AggregateEventEntry e WHERE e.aggregateIdentifier = :id",
                                     Long.class)
                             .setParameter("id", aggregateIdentifier)
                             .getSingleResult());
    }

    public List<Long> sequenceNumbersOfAggregate(String aggregateIdentifier) {
        return query(em -> em.createQuery("""
                                                  SELECT e.aggregateSequenceNumber FROM AggregateEventEntry e
                                                  WHERE e.aggregateIdentifier = :id
                                                  ORDER BY e.aggregateSequenceNumber""", Long.class)
                             .setParameter("id", aggregateIdentifier)
                             .getResultList());
    }

    /** Number of sessions on this database that began a transaction and neither committed nor rolled it back. */
    public long openTransactions() {
        return query(em -> ((Number) em.createNativeQuery("""
                                                                  SELECT count(*) FROM pg_stat_activity
                                                                  WHERE datname = current_database()
                                                                    AND state LIKE 'idle in transaction%'
                                                                    AND pid <> pg_backend_pid()""")
                                       .getSingleResult()).longValue());
    }

    /** Connections currently borrowed from the pool; zero once every unit of work has completed. */
    public int activeConnections() {
        try {
            return (Integer) ManagementFactory.getPlatformMBeanServer().getAttribute(
                    new ObjectName("com.zaxxer.hikari:type=Pool (" + poolName + ")"), "ActiveConnections");
        } catch (JMException e) {
            throw new IllegalStateException("Cannot read the connection pool statistics", e);
        }
    }

    /** A fresh aggregate identifier, so that tests sharing a database never touch each other's aggregates. */
    public static String randomId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void close() {
        axon.shutdown();
        database.close();
    }
}
