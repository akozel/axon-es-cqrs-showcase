package by.akozel.accountverification.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import by.akozel.accountverification.infrastructure.CommandExecutionConfiguration;
import by.akozel.accountverification.infrastructure.CommandExecutionSettings;
import by.akozel.accountverification.infrastructure.JpaEventStoreConfiguration;
import by.akozel.accountverification.infrastructure.PostgresDatabase;
import by.akozel.accountverification.support.PostgresContainer;
import by.akozel.accountverification.support.RequiresPostgresContainer;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.entity.EntityAlreadyExistsForCreationalCommandHandlerException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the real wiring against the PostgreSQL test container.
 */
@RequiresPostgresContainer
class AccountPostgresIntegrationTest {

    private static PostgresDatabase database;
    private static AxonConfiguration axon;
    private static CommandGateway commands;

    @BeforeAll
    static void startApplication() {
        database = PostgresDatabase.connect(PostgresContainer.settings());
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        JpaEventStoreConfiguration.configure(configurer, database.entityManagerFactory());
        CommandExecutionConfiguration.configure(configurer, CommandExecutionSettings.defaults());
        AccountConfiguration.configure(configurer);
        axon = configurer.start();
        commands = axon.getComponent(CommandGateway.class);
    }

    @AfterAll
    static void stopApplication() {
        if (axon != null) {
            axon.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void storesAccountCreatedWithSsnAsAggregateIdentifier() {
        // given
        String ssn = randomSsn();

        // when
        commands.sendAndWait(new CreateAccount(ssn, "John Doe"));

        // then
        List<Object[]> rows = database.entityManagerFactory().createEntityManager()
                                      .createQuery("""
                                                           SELECT e.aggregateType, e.aggregateIdentifier, e.aggregateSequenceNumber
                                                           FROM AggregateEventEntry e
                                                           WHERE e.aggregateIdentifier = :ssn""", Object[].class)
                                      .setParameter("ssn", ssn)
                                      .getResultList();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsExactly(Account.TAG_KEY, ssn, 0L);
    }

    @Test
    void rejectsSecondAccountWithSameSsn() {
        // given
        String ssn = randomSsn();
        commands.sendAndWait(new CreateAccount(ssn, "John Doe"));

        // when / then
        assertThatThrownBy(() -> commands.sendAndWait(new CreateAccount(ssn, "Someone Else")))
                .isInstanceOf(EntityAlreadyExistsForCreationalCommandHandlerException.class);
    }

    private static String randomSsn() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "%03d-%02d-%04d".formatted(random.nextInt(1000), random.nextInt(100), random.nextInt(10000));
    }
}
