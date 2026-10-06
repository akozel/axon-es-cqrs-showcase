package by.akozel.accountverification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies on which thread {@link VirtualThreadCommandBus} runs a command and how it admits commands, against a fake
 * command bus that records the thread of every dispatch and answers as told.
 */
class VirtualThreadCommandBusTest {

    private static final CommandMessage COMMAND = new GenericCommandMessage(new MessageType("test.Ping"), "ping");
    private static final CommandResultMessage RESULT =
            new GenericCommandResultMessage(new MessageType("test.Pong"), "pong");
    /** Stands in for the context of a handler that sends a command; only its presence matters. */
    private static final ProcessingContext HANDLER_CONTEXT = (ProcessingContext) Proxy.newProxyInstance(
            ProcessingContext.class.getClassLoader(), new Class<?>[]{ProcessingContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "HandlerContext";
                default -> throw new UnsupportedOperationException(method.toString());
            });

    private final FakeCommandBus delegate = new FakeCommandBus();

    @Nested
    class Threads {

        private final VirtualThreadCommandBus bus = busAllowing(10);

        @Test
        void runsACommandFromAPlatformThreadOnANewVirtualThread() throws Exception {
            // when
            bus.dispatch(COMMAND, null).get(10, TimeUnit.SECONDS);

            // then
            Thread ranOn = delegate.threads.getFirst();
            assertThat(ranOn.isVirtual()).isTrue();
            assertThat(ranOn.getName()).startsWith("command-");
            assertThat(ranOn).isNotSameAs(Thread.currentThread());
        }

        @Test
        void runsACommandOnTheCallersThreadWhenThatIsAVirtualThread() throws Exception {
            // given
            AtomicReference<Thread> caller = new AtomicReference<>();

            // when
            Thread.ofVirtual().start(() -> {
                caller.set(Thread.currentThread());
                bus.dispatch(COMMAND, null).join();
            }).join(Duration.ofSeconds(10));

            // then
            assertThat(delegate.threads).containsExactly(caller.get());
        }

        @Test
        void runsACommandSentWithAContextOnTheSendersThread() throws Exception {
            // when
            bus.dispatch(COMMAND, HANDLER_CONTEXT).get(10, TimeUnit.SECONDS);

            // then
            assertThat(delegate.threads).containsExactly(Thread.currentThread());
            assertThat(delegate.contexts).containsExactly(HANDLER_CONTEXT);
        }
    }

    @Nested
    class Admission {

        private final VirtualThreadCommandBus bus = busAllowing(1);

        @Test
        void rejectsACommandImmediatelyWhenTheLimitIsReached() {
            // given: the only permit is held by a command that has not finished
            delegate.answer = command -> new CompletableFuture<>();
            bus.dispatch(COMMAND, null);
            awaitDispatches(1);

            // when
            CompletableFuture<CommandResultMessage> rejected = bus.dispatch(COMMAND, null);

            // then: failed already, and never reached the delegate
            assertThat(rejected).isCompletedExceptionally();
            assertThat(causeOf(rejected))
                    .isInstanceOf(CommandConcurrencyLimitExceededException.class)
                    .hasMessageContaining(CommandExecutionSettings.VARIABLE)
                    .hasMessageContaining("1 commands are already executing");
            assertThat(delegate.threads).hasSize(1);
        }

        @Test
        void admitsACommandSentWithAContextWhileTheLimitIsReached() throws Exception {
            // given
            CompletableFuture<CommandResultMessage> first = new CompletableFuture<>();
            delegate.answer = command -> first;
            bus.dispatch(COMMAND, null);
            awaitDispatches(1);
            delegate.answer = command -> CompletableFuture.completedFuture(RESULT);

            // when: e.g. a command the first one's handler sends
            CommandResultMessage nested = bus.dispatch(COMMAND, HANDLER_CONTEXT).get(10, TimeUnit.SECONDS);

            // then
            assertThat(nested).isSameAs(RESULT);
            first.complete(RESULT);
        }

        @ParameterizedTest
        @EnumSource(Outcome.class)
        void admitsTheNextCommandOnceTheExecutingOneEnded(Outcome outcome) throws Exception {
            // given
            delegate.answer = outcome.answer;
            catchThrowable(() -> bus.dispatch(COMMAND, null).get(10, TimeUnit.SECONDS));
            delegate.answer = command -> CompletableFuture.completedFuture(RESULT);

            // when
            CommandResultMessage next = bus.dispatch(COMMAND, null).get(10, TimeUnit.SECONDS);

            // then
            assertThat(next).isSameAs(RESULT);
        }
    }

    @Nested
    class Results {

        private final VirtualThreadCommandBus bus = busAllowing(10);

        @Test
        void passesTheResultThrough() throws Exception {
            // given
            delegate.answer = command -> CompletableFuture.completedFuture(RESULT);

            // when / then
            assertThat(bus.dispatch(COMMAND, null).get(10, TimeUnit.SECONDS)).isSameAs(RESULT);
        }

        @Test
        void passesTheCauseOfAFailureThroughUnwrapped() {
            // given
            IllegalArgumentException failure = new IllegalArgumentException("handler failed");
            delegate.answer = command -> CompletableFuture.failedFuture(failure);

            // when / then
            assertThat(causeOf(bus.dispatch(COMMAND, null))).isSameAs(failure);
        }

        @Test
        void failsInsteadOfHangingWhenTheDelegateThrowsAnError() {
            // given
            Error error = new Error("escaped the delegate");
            delegate.answer = command -> {
                throw error;
            };

            // when / then
            assertThat(causeOf(bus.dispatch(COMMAND, null))).isSameAs(error);
        }

        @Test
        void subscribesHandlersOnTheDelegate() {
            // given
            QualifiedName name = new QualifiedName("test.Ping");
            CommandHandler handler = (command, context) -> {
                throw new UnsupportedOperationException();
            };

            // when
            CommandBus returned = bus.subscribe(name, handler);

            // then
            assertThat(returned).isSameAs(bus);
            assertThat(delegate.subscriptions).containsExactly(name);
        }
    }

    @Nested
    class Shutdown {

        @Test
        void stopsAdmittingCommandsAndWaitsForTheExecutingOnes() throws Exception {
            // given
            VirtualThreadCommandBus bus = new VirtualThreadCommandBus(
                    delegate, new CommandExecutionSettings(2, 1), Duration.ofSeconds(10));
            CompletableFuture<CommandResultMessage> executing = new CompletableFuture<>();
            delegate.answer = command -> executing;
            CompletableFuture<CommandResultMessage> admitted = bus.dispatch(COMMAND, null);
            awaitDispatches(1);

            // when
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(bus::shutdown);

            // then: new commands are refused, those sent from handlers still run, and shutdown waits
            assertThat(waitUntilRejected(bus)).hasMessageContaining("shutting down");
            delegate.answer = command -> CompletableFuture.completedFuture(RESULT);
            assertThat(bus.dispatch(COMMAND, HANDLER_CONTEXT).get(10, TimeUnit.SECONDS)).isSameAs(RESULT);
            assertThat(shutdown).isNotDone();

            executing.complete(RESULT);
            shutdown.get(10, TimeUnit.SECONDS);
            assertThat(admitted.get(10, TimeUnit.SECONDS)).isSameAs(RESULT);
        }

        @Test
        void givesUpWaitingAfterTheTimeout() throws Exception {
            // given: a command that never finishes
            VirtualThreadCommandBus bus = new VirtualThreadCommandBus(
                    delegate, new CommandExecutionSettings(1, 1), Duration.ofMillis(200));
            delegate.answer = command -> new CompletableFuture<>();
            bus.dispatch(COMMAND, null);
            awaitDispatches(1);

            // when / then
            CompletableFuture.runAsync(bus::shutdown).get(10, TimeUnit.SECONDS);
        }
    }

    private VirtualThreadCommandBus busAllowing(int maxConcurrentCommands) {
        return new VirtualThreadCommandBus(delegate, new CommandExecutionSettings(maxConcurrentCommands, 1));
    }

    /** Waits until the shutdown has begun, i.e. until a new command is refused for it. */
    private static Throwable waitUntilRejected(VirtualThreadCommandBus bus) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            CompletableFuture<CommandResultMessage> attempt = bus.dispatch(COMMAND, null);
            if (attempt.isCompletedExceptionally() && causeOf(attempt) instanceof IllegalStateException refused) {
                return refused;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The bus kept admitting commands after shutdown began");
    }

    private void awaitDispatches(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (delegate.threads.size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Expected " + count + " dispatches, saw " + delegate.threads.size());
            }
            Thread.onSpinWait();
        }
    }

    private static Throwable causeOf(CompletableFuture<?> future) {
        Throwable failure = catchThrowable(() -> future.get(10, TimeUnit.SECONDS));
        assertThat(failure).isInstanceOf(ExecutionException.class);
        return failure.getCause();
    }

    /** How the command that held the only permit ended. */
    enum Outcome {
        SUCCEEDED(command -> CompletableFuture.completedFuture(RESULT)),
        FAILED(command -> CompletableFuture.failedFuture(new IllegalStateException("handler failed"))),
        THREW(command -> {
            throw new IllegalStateException("dispatch threw");
        });

        final Function<CommandMessage, CompletableFuture<CommandResultMessage>> answer;

        Outcome(Function<CommandMessage, CompletableFuture<CommandResultMessage>> answer) {
            this.answer = answer;
        }
    }

    /** Records the thread and context of every dispatch, and answers with {@link #answer}. */
    private static final class FakeCommandBus implements CommandBus {

        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final List<ProcessingContext> contexts = new CopyOnWriteArrayList<>();
        final List<QualifiedName> subscriptions = new CopyOnWriteArrayList<>();
        volatile Function<CommandMessage, CompletableFuture<CommandResultMessage>> answer =
                command -> CompletableFuture.completedFuture(RESULT);

        @Override
        public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                                @Nullable ProcessingContext processingContext) {
            threads.add(Thread.currentThread());
            if (processingContext != null) {
                contexts.add(processingContext);
            }
            return answer.apply(command);
        }

        @Override
        public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
            subscriptions.add(name);
            return this;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
        }
    }
}
