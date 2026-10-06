package by.akozel.accountverification.domain.account;

import by.akozel.accountverification.application.account.AccountConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.modelling.entity.EntityAlreadyExistsForCreationalCommandHandlerException;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final String SSN = "123-45-6789";

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(AccountConfiguration.configure(EventSourcingConfigurer.create()));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class WhenCreatingAccount {

        @Test
        void publishesAccountCreated() {
            // given
            fixture.given()
                   .noPriorActivity()
                   // when
                   .when()
                   .command(new CreateAccount(SSN, "John Doe"))
                   // then
                   .then()
                   .success()
                   .events(new AccountCreated(SSN, "John Doe"));
        }

        @Test
        void rejectsSsnOfExistingAccount() {
            // given
            fixture.given()
                   .event(new AccountCreated(SSN, "John Doe"))
                   // when
                   .when()
                   .command(new CreateAccount(SSN, "Someone Else"))
                   // then
                   .then()
                   .exception(EntityAlreadyExistsForCreationalCommandHandlerException.class)
                   .noEvents();
        }

        @Test
        void allowsDifferentSsnsToCoexist() {
            // given
            fixture.given()
                   .event(new AccountCreated(SSN, "John Doe"))
                   // when
                   .when()
                   .command(new CreateAccount("987-65-4321", "Jane Roe"))
                   // then
                   .then()
                   .success()
                   .events(new AccountCreated("987-65-4321", "Jane Roe"));
        }

        @Test
        void rejectsMalformedSsn() {
            // given
            fixture.given()
                   .noPriorActivity()
                   // when
                   .when()
                   .command(new CreateAccount("123456789", "John Doe"))
                   // then
                   .then()
                   .exception(IllegalArgumentException.class, "SSN must match NNN-NN-NNNN")
                   .noEvents();
        }

        @Test
        void rejectsBlankHolderName() {
            // given
            fixture.given()
                   .noPriorActivity()
                   // when
                   .when()
                   .command(new CreateAccount(SSN, " "))
                   // then
                   .then()
                   .exception(IllegalArgumentException.class, "Account holder name must not be blank")
                   .noEvents();
        }
    }
}
