package by.akozel.axon.foundation.testing;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * A minimal event-sourced entity for the tests of this module, which knows no service's domain. Its events are tagged
 * with {@link #TAG_KEY}, which the JPA event storage engine stores as the aggregate type.
 */
@EventSourcedEntity(tagKey = TestAggregate.TAG_KEY)
public class TestAggregate {

    public static final String TAG_KEY = "TestAggregate";

    @Command(namespace = "by.akozel.axon.foundation.testing", routingKey = "id")
    public record CreateTestAggregate(@TargetEntityId String id) {}

    @Event(namespace = "by.akozel.axon.foundation.testing")
    public record TestAggregateCreated(@EventTag(key = TAG_KEY) String id) {}

    @CommandHandler // creational: static, no entity exists yet
    public static void handle(CreateTestAggregate command, EventAppender appender) {
        appender.append(new TestAggregateCreated(command.id()));
    }

    @EventSourcingHandler
    void on(TestAggregateCreated event) {}

    @EntityCreator
    public TestAggregate() {}

    /** Registers the entity; pass it to {@link PostgresAxonEnvironment#start(java.util.function.Consumer,
     * java.util.function.Consumer)}. */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(EventSourcedEntityModule.autodetected(String.class, TestAggregate.class));
    }
}
