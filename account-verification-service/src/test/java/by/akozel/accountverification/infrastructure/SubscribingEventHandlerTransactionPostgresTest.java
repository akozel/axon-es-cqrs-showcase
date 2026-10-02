package by.akozel.accountverification.infrastructure;

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

import by.akozel.accountverification.account.Account;
import by.akozel.accountverification.support.PostgresAxonEnvironment;
import by.akozel.accountverification.support.RequiresPostgresContainer;
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

    public record AddNote(String ssn, String text) {}

    public record NoteAdded(@EventTag(key = Account.TAG_KEY) String ssn, String text) {}

    /** Appends a note to an account after reading the account, like a decision model would. */
    public static class NotesCommandHandler {

        @CommandHandler
        public void handle(AddNote command, ProcessingContext context) {
            context.component(EventStore.class)
                   .transaction(context)
                   .source(SourcingCondition.conditionFor(EventCriteria.havingTags(Account.TAG_KEY, command.ssn())))
                   .reduce(0, (count, entry) -> count + 1)
                   .join();
            EventAppender.forContext(context).append(new NoteAdded(command.ssn(), command.text()));
        }
    }

    /** An automation that answers a note asking for it with a follow-up note on the account it picks. */
    public static class FollowUp {

        private final UnaryOperator<String> targetAccount;
        private final CyclicBarrier beforeSending;

        FollowUp(UnaryOperator<String> targetAccount, CyclicBarrier beforeSending) {
            this.targetAccount = targetAccount;
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
            context.component(CommandGateway.class).sendAndWait(new AddNote(targetAccount.apply(event.ssn()), "done"),
                                                                 context);
        }
    }

    /** A read model kept in the transaction of the events, through the unit of work's {@code EntityManager}. */
    public static class NotesProjection {

        @EventHandler
        public void on(NoteAdded event, ProcessingContext context) {
            context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY).get().apply(entityManager -> {
                entityManager.persist(readModelRowOf(event.ssn()));
                return null;
            }).join();
        }
    }

    @Test
    void refusesACommandToTheSameAccountInsteadOfWaitingForeverForItsOwnLock() {
        try (PostgresAxonEnvironment env = PostgresAxonEnvironment.start(pool -> {}, configurer -> {
            registerNotes(configurer);
            registerSubscribing(configurer, "follow-up", new FollowUp(ssn -> ssn, null));
        })) {
            // given
            String ssn = PostgresAxonEnvironment.randomSsn();

            // when: the follow-up would append to the account whose note is written but not yet committed
            Throwable failure = catchThrowable(
                    () -> env.commands().sendAndWait(new AddNote(ssn, ASKS_FOR_FOLLOW_UP)));

            // then: it fails at once. Without the check, the follow-up's insert waits for the note's transaction,
            // which waits for the follow-up on the same thread; PostgreSQL cannot detect that.
            assertThat(failure).hasStackTraceContaining(REFUSED);
            assertThat(env.countEventsOfAggregate(ssn)).isZero();
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
                            ssn -> PostgresAxonEnvironment.randomSsn(), bothHoldTheirConnection));
                })) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // when: both pooled connections are held by commands whose handlers each send a follow-up
                Future<Throwable> first = executor.submit(() -> catchThrowable(() -> env.commands().sendAndWait(
                        new AddNote(PostgresAxonEnvironment.randomSsn(), ASKS_FOR_FOLLOW_UP))));
                Future<Throwable> second = executor.submit(() -> catchThrowable(() -> env.commands().sendAndWait(
                        new AddNote(PostgresAxonEnvironment.randomSsn(), ASKS_FOR_FOLLOW_UP))));

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
            String ssn = PostgresAxonEnvironment.randomSsn();

            // when
            env.commands().sendAndWait(new AddNote(ssn, "first"));

            // then: the event and the read model row were committed together
            assertThat(env.countEventsOfAggregate(ssn)).isEqualTo(1);
            assertThat(env.countEventsOfAggregate(readModelIdOf(ssn))).isEqualTo(1);
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
    private static AggregateEventEntry readModelRowOf(String ssn) {
        return new AggregateEventEntry(UUID.randomUUID().toString(), "test.NoteSummary", "1", new byte[]{1},
                                       "{}".getBytes(UTF_8), Instant.now(), "NoteSummary", readModelIdOf(ssn), 0L);
    }

    private static String readModelIdOf(String ssn) {
        return "read-model-" + ssn;
    }
}
