package by.akozel.axon.foundation.persistence;

import java.util.function.Consumer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;

/**
 * Connection pool plus the JPA {@link EntityManagerFactory} backing the Axon event store.
 * <p>
 * Note: {@link AggregateEventEntry} declares its payload and metadata as {@code @Lob byte[]}, which Hibernate stores
 * as PostgreSQL large objects ({@code oid}) rather than {@code bytea}. Read them with {@code lo_get(payload)}.
 */
public final class PostgresDatabase implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;

    private PostgresDatabase(HikariDataSource dataSource, EntityManagerFactory entityManagerFactory) {
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
    }

    public static PostgresDatabase connect(DatabaseSettings settings) {
        return connect(settings, pool -> {});
    }

    /**
     * @param poolCustomizer adjusts the connection pool, e.g. its size or timeouts, before it is started
     */
    public static PostgresDatabase connect(DatabaseSettings settings, Consumer<HikariConfig> poolCustomizer) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(settings.jdbcUrl());
        pool.setUsername(settings.user());
        pool.setPassword(settings.password());
        // Bounded waits: a writer blocked on another transaction's lock, or a transaction left open by a stuck
        // client, fails instead of holding its connection (and the aggregate's lock) forever.
        pool.addDataSourceProperty("options", settings.sessionOptions());
        poolCustomizer.accept(pool);
        HikariDataSource dataSource = new HikariDataSource(pool);

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                // Skeleton only: lets Hibernate create/extend the event store tables. Replace with
                // explicit migrations (e.g. Flyway) before this runs anywhere that matters.
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "update")
                .build();
        try {
            EntityManagerFactory entityManagerFactory = new MetadataSources(registry)
                    .addAnnotatedClass(AggregateEventEntry.class)
                    .buildMetadata()
                    .buildSessionFactory();
            return new PostgresDatabase(dataSource, entityManagerFactory);
        } catch (RuntimeException e) {
            StandardServiceRegistryBuilder.destroy(registry);
            dataSource.close();
            throw e;
        }
    }

    public EntityManagerFactory entityManagerFactory() {
        return entityManagerFactory;
    }

    @Override
    public void close() {
        entityManagerFactory.close();
        dataSource.close();
    }
}
