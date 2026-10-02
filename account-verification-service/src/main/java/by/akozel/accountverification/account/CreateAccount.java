package by.akozel.accountverification.account;

import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Creates an {@link Account}. The SSN is the account identifier.
 * <p>
 * {@code @TargetEntityId} makes Axon load the account by SSN before the handler runs, so creating an account
 * that already exists fails instead of silently appending a second creation event.
 */
@Command(namespace = "by.akozel.accountverification.account", routingKey = "ssn")
public record CreateAccount(@TargetEntityId String ssn, String holderName) {}
