package by.akozel.accountverification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import by.akozel.accountverification.support.PostgresAxonEnvironment;
import by.akozel.accountverification.support.RequiresPostgresContainer;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies on which thread commands run with the application's real wiring ({@link VirtualThreadCommandBus} in front
 * of the JPA event store). Runs against the PostgreSQL test container.
 */
@RequiresPostgresContainer
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CommandExecutionPostgresTest {

    public record Touch(String id) {}

    public record TouchBoth(String id, String otherId) {}

    public record Touched(@EventTag(key = "Touch") String id) {}

    /** Writes an event for every command and records the thread it ran on. */
    public static class TouchHandlers {

        final List<Thread> threads = new CopyOnWriteArrayList<>();

        @CommandHandler
        public void handle(Touch command, EventAppender appender) {
            threads.add(Thread.currentThread());
            appender.append(new Touched(command.id()));
        }

        /** Sends the other command before writing its own event, i.e. before its unit of work holds a transaction. */
        @CommandHandler
        public void handle(TouchBoth command, CommandDispatcher dispatcher, EventAppender appender) {
            threads.add(Thread.currentThread());
            dispatcher.send(new Touch(command.otherId()), Object.class).join();
            appender.append(new Touched(command.id()));
        }
    }

    private final TouchHandlers handlers = new TouchHandlers();

    @Test
    void runsCommandHandlersOnVirtualThreads() {
        try (PostgresAxonEnvironment env = PostgresAxonEnvironment.start(pool -> {}, this::registerHandlers)) {
            // given
            String id = UUID.randomUUID().toString();

            // when: sent from a platform thread
            env.commands().sendAndWait(new Touch(id));

            // then
            assertThat(handlers.threads).singleElement().satisfies(thread -> assertThat(thread.isVirtual()).isTrue());
            assertThat(env.countEventsOfAggregate(id)).isEqualTo(1);
        }
    }

    @Test
    void runsACommandSentWithTheHandlersContextOnTheSameThread() {
        try (PostgresAxonEnvironment env = PostgresAxonEnvironment.start(pool -> {}, this::registerHandlers)) {
            // given
            String id = UUID.randomUUID().toString();
            String otherId = UUID.randomUUID().toString();

            // when
            env.commands().sendAndWait(new TouchBoth(id, otherId));

            // then: one virtual thread, two committed units of work, nothing left open
            assertThat(handlers.threads).hasSize(2);
            assertThat(handlers.threads.getFirst().isVirtual()).isTrue();
            assertThat(handlers.threads.getLast()).isSameAs(handlers.threads.getFirst());
            assertThat(env.countEventsOfAggregate(id)).isEqualTo(1);
            assertThat(env.countEventsOfAggregate(otherId)).isEqualTo(1);
            assertThat(env.activeConnections()).isZero();
            assertThat(env.openTransactions()).isZero();
        }
    }

    private void registerHandlers(EventSourcingConfigurer configurer) {
        configurer.registerCommandHandlingModule(
                CommandHandlingModule.named("touch")
                                     .commandHandlers()
                                     .autodetectedCommandHandlingComponent(c -> handlers));
    }
}
