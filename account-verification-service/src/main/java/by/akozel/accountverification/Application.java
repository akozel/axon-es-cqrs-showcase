package by.akozel.accountverification;

import by.akozel.accountverification.account.AccountConfiguration;
import by.akozel.accountverification.infrastructure.DatabaseSettings;
import by.akozel.accountverification.infrastructure.JpaEventStoreConfiguration;
import by.akozel.accountverification.infrastructure.PostgresDatabase;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private Application() {}

    public static void main(String[] args) throws InterruptedException {
        DatabaseSettings settings = DatabaseSettings.fromEnvironment();
        PostgresDatabase database = PostgresDatabase.connect(settings);

        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        JpaEventStoreConfiguration.configure(configurer, database.entityManagerFactory());
        AccountConfiguration.configure(configurer);
        AxonConfiguration axon = configurer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            axon.shutdown();
            database.close();
        }));

        logger.info("account-verification-service started, event store: {}", settings);
        Thread.currentThread().join();
    }
}
