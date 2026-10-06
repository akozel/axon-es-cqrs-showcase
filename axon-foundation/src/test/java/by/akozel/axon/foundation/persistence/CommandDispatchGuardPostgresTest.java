package by.akozel.axon.foundation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import by.akozel.axon.foundation.testing.TestAggregate;
import by.akozel.axon.foundation.testing.PostgresAxonEnvironment;
import by.akozel.axon.foundation.testing.RequiresPostgresContainer;
import com.zaxxer.hikari.HikariConfig;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies {@link JpaUnitOfWorkTransactionManager#commandDispatchInterceptor()}: a command sent with the context of a
 * unit of work that has written and not yet committed is refused at once, also when it is sent from another thread. A
 * command sent without the context is not checked; only the database's and the pool's timeouts end it. Runs against
 * the PostgreSQL test container.
 */
@RequiresPostgresContainer
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CommandDispatchGuardPostgresTest {

    private static final String REFUSED = "was sent with the processing context of a unit of work that has written";
    private static final String ASKS_FOR_FOLLOW_UP = "please follow up";

    public record AddRemark(String id, String text) {}

    public record RemarkAdded(@EventTag(key = TestAggregate.TAG_KEY) String id, String text) {}

    /** Appends a remark to an aggregate after reading the aggregate, like a decision model would. */
    public static class RemarkCommandHandler {

        @CommandHandler
        public void handle(AddRemark command, ProcessingContext context) {
            context.component(EventStore.class)
                   .transaction(context)
                   .source(SourcingCondition.conditionFor(EventCriteria.havingTags(TestAggregate.TAG_KEY, command.id())))
                   .reduce(0, (count, entry) -> count + 1)
                   .join();
            EventAppender.forContext(context).append(new RemarkAdded(command.id(), command.text()));
        }
    }

    /** Answers a remark asking for it with a follow-up remark on the same aggregate, sent with its own context. */
    public static class FollowUp {

        private final @Nullable ExecutorService sender;

        /** @param sender the thread to send the follow-up from, or {@code null} for the handler's own */
        FollowUp(@Nullable ExecutorService sender) {
            this.sender = sender;
        }

        @EventHandler
        public void on(RemarkAdded event, CommandDispatcher dispatcher) {
            if (!event.text().equals(ASKS_FOR_FOLLOW_UP)) {
                return;
            }
            AddRemark followUp = new AddRemark(event.id(), "done");
            if (sender == null) {
                dispatcher.send(followUp, Object.class).join();
            } else {
                CompletableFuture.supplyAsync(() -> dispatcher.send(followUp, Object.class).join(), sender).join();
            }
        }
    }

    /** Like {@link FollowUp}, but sends without its context, which the architecture rules forbid in production code. */
    public static class FollowUpWithoutContext {

        private final UnaryOperator<String> targetAggregate;
        private final @Nullable CyclicBarrier beforeSending;

        FollowUpWithoutContext(UnaryOperator<String> targetAggregate, @Nullable CyclicBarrier beforeSending) {
            this.targetAggregate = targetAggregate;
            this.beforeSending = beforeSending;
        }

        @EventHandler
        public void on(RemarkAdded event, ProcessingContext context) throws Exception {
            if (!event.text().equals(ASKS_FOR_FOLLOW_UP)) {
                return;
            }
            if (beforeSending != null) {
                beforeSending.await(30, TimeUnit.SECONDS);
            }
            context.component(CommandGateway.class)
                   .sendAndWait(new AddRemark(targetAggregate.apply(event.id()), "done"));
        }
    }

    @Test
    void refusesACommandSentWithTheContextOfAUnitOfWorkThatHoldsItsTransaction() {
        try (PostgresAxonEnvironment env = startWith(new FollowUp(null), pool -> {})) {
            // given
            String id = PostgresAxonEnvironment.randomId();

            // when: the follow-up would append to the aggregate whose remark is written but not yet committed
            Throwable failure = catchThrowable(
                    () -> env.commands().sendAndWait(new AddRemark(id, ASKS_FOR_FOLLOW_UP)));

            // then
            assertThat(failure).hasStackTraceContaining(REFUSED);
            assertThat(env.countEventsOfAggregate(id)).isZero();
            assertThat(env.activeConnections()).isZero();
        }
    }

    @Test
    void refusesItAlsoWhenTheCommandIsSentFromAnotherThread() {
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try (PostgresAxonEnvironment env = startWith(new FollowUp(otherThread), pool -> {})) {
            // given
            String id = PostgresAxonEnvironment.randomId();

            // when
            Throwable failure = catchThrowable(
                    () -> env.commands().sendAndWait(new AddRemark(id, ASKS_FOR_FOLLOW_UP)));

            // then: refused at once, instead of waiting for the remark's lock until lock_timeout ends it
            assertThat(failure).hasStackTraceContaining(REFUSED);
            assertThat(env.countEventsOfAggregate(id)).isZero();
            assertThat(env.activeConnections()).isZero();
        } finally {
            otherThread.shutdownNow();
        }
    }

    @Test
    void leavesACommandSentWithoutTheContextToTheLockTimeout() {
        try (PostgresAxonEnvironment env = startWith(
                new FollowUpWithoutContext(id -> id, null),
                pool -> pool.addDataSourceProperty("options", "-c lock_timeout=500ms"))) {
            // given
            String id = PostgresAxonEnvironment.randomId();

            // when: the follow-up to the same aggregate waits for the lock of the remark its sender has written
            Throwable failure = catchThrowable(
                    () -> env.commands().sendAndWait(new AddRemark(id, ASKS_FOR_FOLLOW_UP)));

            // then: nothing refused it; PostgreSQL's lock_timeout ended the wait, and nothing was stored
            assertThat(failure).hasStackTraceContaining("canceling statement due to lock timeout");
            assertThat(env.countEventsOfAggregate(id)).isZero();
            assertThat(env.activeConnections()).isZero();
        }
    }

    @Test
    void leavesCommandsSentWithoutTheContextToThePoolTimeout() throws Exception {
        CyclicBarrier bothHoldTheirConnection = new CyclicBarrier(2);
        ExecutorService senders = Executors.newFixedThreadPool(2);
        try (PostgresAxonEnvironment env = startWith(
                new FollowUpWithoutContext(id -> PostgresAxonEnvironment.randomId(), bothHoldTheirConnection),
                pool -> {
                    pool.setMaximumPoolSize(2);
                    pool.setConnectionTimeout(1_000);
                })) {
            // when: both pooled connections are held by commands whose follow-ups each need another one
            Future<Throwable> first = senders.submit(() -> catchThrowable(() -> env.commands().sendAndWait(
                    new AddRemark(PostgresAxonEnvironment.randomId(), ASKS_FOR_FOLLOW_UP))));
            Future<Throwable> second = senders.submit(() -> catchThrowable(() -> env.commands().sendAndWait(
                    new AddRemark(PostgresAxonEnvironment.randomId(), ASKS_FOR_FOLLOW_UP))));

            // then: nothing refused them; the pool's connection timeout ended the wait
            assertThat(first.get(30, TimeUnit.SECONDS)).hasStackTraceContaining("Connection is not available");
            assertThat(second.get(30, TimeUnit.SECONDS)).hasStackTraceContaining("Connection is not available");
            assertThat(env.activeConnections()).isZero();
        } finally {
            senders.shutdownNow();
        }
    }

    private static PostgresAxonEnvironment startWith(Object followUp, Consumer<HikariConfig> poolCustomizer) {
        return PostgresAxonEnvironment.start(poolCustomizer, configurer -> {
            configurer.registerCommandHandlingModule(
                    CommandHandlingModule.named("remarks")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(c -> new RemarkCommandHandler()));
            registerSubscribing(configurer, followUp);
        });
    }

    private static void registerSubscribing(EventSourcingConfigurer configurer, Object eventHandler) {
        configurer.messaging(messaging -> messaging.eventProcessing(processing -> processing.subscribing(
                subscribing -> subscribing.processor(
                        "follow-up",
                        module -> module.eventHandlingComponents(
                                                components -> components.autodetected("follow-up", c -> eventHandler))
                                        .notCustomized()))));
    }
}
