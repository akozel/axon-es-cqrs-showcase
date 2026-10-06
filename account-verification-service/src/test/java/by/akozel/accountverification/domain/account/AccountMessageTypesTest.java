package by.akozel.accountverification.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import by.akozel.accountverification.application.account.AccountConfiguration;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The message types Axon derives for the account's messages are their names in the event store. They must not follow
 * the Java package of the classes: events already written under the old name could no longer be read.
 */
class AccountMessageTypesTest {

    private AxonConfiguration axon;
    private MessageTypeResolver types;

    @BeforeEach
    void setUp() {
        axon = AccountConfiguration.configure(EventSourcingConfigurer.create()).start();
        types = axon.getComponent(MessageTypeResolver.class);
    }

    @AfterEach
    void tearDown() {
        axon.shutdown();
    }

    @Test
    void accountCreatedKeepsItsStoredName() {
        // when
        MessageType type = types.resolveOrThrow(AccountCreated.class);

        // then
        assertThat(type.qualifiedName().toString()).isEqualTo("by.akozel.accountverification.account.AccountCreated");
        assertThat(type.version()).isEqualTo("1");
    }

    @Test
    void createAccountKeepsItsName() {
        // when
        MessageType type = types.resolveOrThrow(CreateAccount.class);

        // then
        assertThat(type.qualifiedName().toString()).isEqualTo("by.akozel.accountverification.account.CreateAccount");
    }
}
