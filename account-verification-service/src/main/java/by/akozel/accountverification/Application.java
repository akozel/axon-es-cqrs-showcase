package by.akozel.accountverification;

import by.akozel.accountverification.application.account.AccountConfiguration;
import by.akozel.accountverification.infrastructure.messaging.CommandExecutionConfiguration;
import by.akozel.accountverification.infrastructure.messaging.CommandExecutionSettings;
import by.akozel.accountverification.infrastructure.persistence.DatabaseSettings;
import by.akozel.accountverification.infrastructure.persistence.JpaEventStoreConfiguration;
import by.akozel.accountverification.infrastructure.persistence.PostgresDatabase;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point and composition root: reads the settings and wires the layers together. The only class allowed to
 * depend on the infrastructure.
 */
public final class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private Application() {}

    public static void main(String[] args) throws InterruptedException {
        DatabaseSettings settings = DatabaseSettings.fromEnvironment();
        CommandExecutionSettings commandExecution = CommandExecutionSettings.fromEnvironment();
        PostgresDatabase database = PostgresDatabase.connect(settings);

        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        JpaEventStoreConfiguration.configure(configurer, database.entityManagerFactory());
        CommandExecutionConfiguration.configure(configurer, commandExecution);
        AccountConfiguration.configure(configurer);
        AxonConfiguration axon = configurer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            axon.shutdown();
            database.close();
        }));

        logger.info("account-verification-service started, event store: {}, commands on virtual threads: {}",
                    settings, commandExecution);
        Thread.currentThread().join();
    }
}
