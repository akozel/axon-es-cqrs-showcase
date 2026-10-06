package by.akozel.accountverification.domain.account;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

/**
 * An {@link Account} was created. Tagged with {@code Account → ssn}: the JPA event storage engine stores the tag
 * key as the aggregate type and the tag value as the aggregate identifier.
 * <p>
 * The namespace is part of the stored event type and must not follow the Java package: events already written under
 * it could no longer be read.
 */
@Event(namespace = "by.akozel.accountverification.account", version = "1")
public record AccountCreated(@EventTag(key = Account.TAG_KEY) String ssn, String holderName) {}
