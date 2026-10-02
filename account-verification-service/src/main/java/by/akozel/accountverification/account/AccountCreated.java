package by.akozel.accountverification.account;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

/**
 * An {@link Account} was created. Tagged with {@code Account → ssn}: the JPA event storage engine stores the tag
 * key as the aggregate type and the tag value as the aggregate identifier.
 */
@Event(namespace = "by.akozel.accountverification.account", version = "1")
public record AccountCreated(@EventTag(key = Account.TAG_KEY) String ssn, String holderName) {}
