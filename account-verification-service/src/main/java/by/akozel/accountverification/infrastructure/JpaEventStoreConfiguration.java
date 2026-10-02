package by.akozel.accountverification.infrastructure;

import jakarta.persistence.EntityManagerFactory;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.SQLStateResolver;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

/**
 * Stores events in a relational database through the open-source {@link AggregateBasedJpaEventStorageEngine}.
 */
public final class JpaEventStoreConfiguration {

    private JpaEventStoreConfiguration() {}

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer,
                                                    EntityManagerFactory entityManagerFactory) {
        JpaUnitOfWorkTransactionManager transactionManager = new JpaUnitOfWorkTransactionManager(entityManagerFactory);
        return configurer
                .componentRegistry(registry -> registry.registerComponent(
                        TransactionManager.class, config -> transactionManager))
                .registerEventStorageEngine(config -> new AggregateBasedJpaEventStorageEngine(
                        transactionManager.executorProvider(),
                        config.getComponent(EventConverter.class),
                        // Without a resolver a duplicate (aggregate id, sequence) key is not reported as a
                        // concurrent-modification conflict. 23505 = unique_violation; other integrity
                        // violations (not null, foreign key, check) are not conflicts and must not be retried.
                        engine -> engine.persistenceExceptionResolver(new SQLStateResolver("23505"))));
    }
}
