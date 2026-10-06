package by.akozel.accountverification.domain.account;

import java.util.regex.Pattern;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

/**
 * An account, identified by the SSN of its holder in the canonical {@code NNN-NN-NNNN} form.
 */
@EventSourcedEntity(tagKey = Account.TAG_KEY)
public class Account {

    /** Tag key (= aggregate type in the event store) shared by every event of an account. */
    public static final String TAG_KEY = "Account";

    private static final Pattern SSN_FORMAT = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");

    private String ssn;

    @CommandHandler // creational: static, no entity exists yet
    public static void handle(CreateAccount command, EventAppender appender) {
        if (command.ssn() == null || !SSN_FORMAT.matcher(command.ssn()).matches()) {
            throw new IllegalArgumentException("SSN must match NNN-NN-NNNN");
        }
        if (command.holderName() == null || command.holderName().isBlank()) {
            throw new IllegalArgumentException("Account holder name must not be blank");
        }
        appender.append(new AccountCreated(command.ssn(), command.holderName()));
    }

    @EventSourcingHandler
    void on(AccountCreated event) {
        this.ssn = event.ssn();
    }

    @EntityCreator
    public Account() {}
}
