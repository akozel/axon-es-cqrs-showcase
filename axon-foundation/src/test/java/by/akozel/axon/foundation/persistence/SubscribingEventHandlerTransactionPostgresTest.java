package by.akozel.axon.foundation.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import by.akozel.axon.foundation.testing.TestAggregate;
import by.akozel.axon.foundation.testing.PostgresAxonEnvironment;
import by.akozel.axon.foundation.testing.RequiresPostgresContainer;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies how {@link JpaUnitOfWorkTransactionManager} treats subscribing event handlers. They run inside the
 * publishing unit of work after its events were written, i.e. while it holds its transaction: writing through that
 * unit of work's {@code EntityManager} works, a command sent with its context fails at once. Runs against the
 * PostgreSQL test container.
 */
@RequiresPostgresContainer
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SubscribingEventHandlerTransactionPostgresTest {

    private static final String REFUSED = "was sent with the processing context of a unit of work that has written";
    private static final String ASKS_FOR_FOLLOW_UP = "please follow up";

    public record AddNote(String id, String text) {}

    public record NoteAdded(@EventTag(key = TestAggregate.TAG_KEY) String id, String text) {}

    /** Appends a note to an aggregate after reading the aggregate, like a decision model would. */
    public static class NotesCommandHandler {

        @CommandHandler
        public void handle(AddNote command, ProcessingContext context) {
            context.component(EventStore.class)
                   .transaction(context)
                   .source(SourcingCondition.conditionFor(EventCriteria.havingTags(TestAggregate.TAG_KEY, command.id())))
                   .reduce(0, (count, entry) -> count + 1)
                   .join();
            EventAppender.forContext(context).append(new NoteAdded(command.id(), command.text()));
        }
    }

    /** An automation that answers a note asking for it with a follow-up note on the aggregate it picks. */
    public static class FollowUp {

        private final UnaryOperator<String> targetAggregate;
        private final CyclicBarrier beforeSending;

        FollowUp(UnaryOperator<String> targetAggregate, CyclicBarrier beforeSending) {
            this.targetAggregate = targetAggregate;
            this.beforeSending = beforeSending;
        }

        @EventHandler
        public void on(NoteAdded event, ProcessingContext context) throws Exception {
            if (!event.text().equals(ASKS_FOR_FOLLOW_UP)) {
                return;
            }
            if (beforeSending != null) {
                beforeSending.await(30, TimeUnit.SECONDS);
            }
            context.component(CommandGateway.class).sendAndWait(new AddNote(targetAggregate.apply(event.id()), "done"),
                                                                 context);
        }
    }

    /** A read model kept in the transaction of the events, through the unit of work's {@code EntityManager}. */
    public static class NotesProjection {

        @EventHandler
        public void on(NoteAdded event, ProcessingContext context) {
            context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY).get().apply(entityManager -> {
                entityManager.persist(readModelRowOf(event.id()));
                return null;
            }).join();
        }
    }

    @Test
    void refusesACommandToTheSameAggregateInsteadOfWaitingForeverForItsOwnLock() {
        try (PostgresAxonEnvironment env = PostgresAxonEnvironment.start(pool -> {}, configurer -> {
            registerNotes(configurer);
            registerSubscribing(configurer, "follow-up", new FollowUp(id -> id, null));
        })) {
            // given
            String id = PostgresAxonEnvironment.randomId();

            // when: the follow-up would append to the aggregate whose note is written but not yet committed
            Throwable failure = catchThrowable(
                    () -> env.commands().sendAndWait(new AddNote(id, ASKS_FOR_FOLLOW_UP)));

            // then: it fails at once. Without the check, the follow-up's insert waits for the note's transaction,
            // which waits for the follow-up on the same thread; PostgreSQL cannot detect that.
            assertThat(failure).hasStackTraceContaining(REFUSED);
            assertThat(env.countEventsOfAggregate(id)).isZero();
            assertThat(env.activeConnections()).isZero();
        }
    }

    @Test
    void refusesCommandsThatNeedASecondConnectionInsteadOfExhaustingThePool() throws Exception {
        CyclicBarrier bothHoldTheirConnection = new CyclicBarrier(2);
        try (PostgresAxonEnvironment env = PostgresAxonEnvironment.start(
                pool -> {
                    pool.setMaximumPoolSize(2);
                    pool.setConnectionTimeout(3_000);
                },
                configurer -> {
                    registerNotes(configurer);
                    registerSubscribing(configurer, "follow-up", new FollowUp(
                            id -> PostgresAxonEnvironment.randomId(), bothHoldTheirConnection));
                })) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // when: both pooled connections are held by commands whose handlers each send a follow-up
                Future<Throwable> first = executor.submit(() -> catchThrowable(() -> env.commands().sendAndWait(
                        new AddNote(PostgresAxonEnvironment.randomId(), ASKS_FOR_FOLLOW_UP))));
                Future<Throwable> second = executor.submit(() -> catchThrowable(() -> env.commands().sendAndWait(
                        new AddNote(PostgresAxonEnvironment.randomId(), ASKS_FOR_FOLLOW_UP))));

                // then: both fail at once instead of waiting for a connection that never becomes free
                assertThat(first.get(30, TimeUnit.SECONDS)).hasStackTraceContaining(REFUSED);
                assertThat(second.get(30, TimeUnit.SECONDS)).hasStackTraceContaining(REFUSED);
                assertThat(env.activeConnections()).isZero();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void letsAProjectionWriteInTheSameTransactionOverASingleConnection() {
        try (PostgresAxonEnvironment env = PostgresAxonEnvironment.start(
                pool -> {
                    pool.setMaximumPoolSize(1);
                    pool.setConnectionTimeout(5_000);
                },
                configurer -> {
                    registerNotes(configurer);
                    registerSubscribing(configurer, "notes-projection", new NotesProjection());
                })) {
            // given
            String id = PostgresAxonEnvironment.randomId();

            // when
            env.commands().sendAndWait(new AddNote(id, "first"));

            // then: the event and the read model row were committed together
            assertThat(env.countEventsOfAggregate(id)).isEqualTo(1);
            assertThat(env.countEventsOfAggregate(readModelIdOf(id))).isEqualTo(1);
        }
    }

    private static void registerNotes(EventSourcingConfigurer configurer) {
        configurer.registerCommandHandlingModule(
                CommandHandlingModule.named("notes")
                                     .commandHandlers()
                                     .autodetectedCommandHandlingComponent(c -> new NotesCommandHandler()));
    }

    private static void registerSubscribing(EventSourcingConfigurer configurer, String name, Object eventHandler) {
        configurer.messaging(messaging -> messaging.eventProcessing(processing -> processing.subscribing(
                subscribing -> subscribing.processor(
                        name,
                        module -> module.eventHandlingComponents(
                                                components -> components.autodetected(name, c -> eventHandler))
                                        .notCustomized()))));
    }

    /** The only mapped entity stands in for a read model table. */
    private static AggregateEventEntry readModelRowOf(String id) {
        return new AggregateEventEntry(UUID.randomUUID().toString(), "test.NoteSummary", "1", new byte[]{1},
                                       "{}".getBytes(UTF_8), Instant.now(), "NoteSummary", readModelIdOf(id), 0L);
    }

    private static String readModelIdOf(String id) {
        return "read-model-" + id;
    }
}
