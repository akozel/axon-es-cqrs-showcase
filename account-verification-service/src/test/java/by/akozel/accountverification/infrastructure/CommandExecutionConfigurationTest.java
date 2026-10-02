package by.akozel.accountverification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies how {@link CommandExecutionConfiguration} wires the {@link VirtualThreadCommandBus} into Axon, with the
 * in-memory event store.
 */
class CommandExecutionConfigurationTest {

    public record Ping(String id) {}

    /** Records the thread every command was handled on. */
    public static class PingHandler {

        final List<Thread> threads = new CopyOnWriteArrayList<>();

        @CommandHandler
        public void handle(Ping command) {
            threads.add(Thread.currentThread());
        }
    }

    private final PingHandler handler = new PingHandler();
    private final List<Thread> dispatchedOn = new CopyOnWriteArrayList<>();
    private AxonConfiguration axon;

    @BeforeEach
    void start() {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
        configurer.registerCommandHandlingModule(
                CommandHandlingModule.named("ping")
                                     .commandHandlers()
                                     .autodetectedCommandHandlingComponent(c -> handler));
        configurer.messaging(messaging -> messaging.registerCommandDispatchInterceptor(
                c -> (message, context, chain) -> {
                    dispatchedOn.add(Thread.currentThread());
                    return chain.proceed(message, context);
                }));
        CommandExecutionConfiguration.configure(configurer, new CommandExecutionSettings(10, 1));
        axon = configurer.start();
    }

    @AfterEach
    void stop() {
        axon.shutdown();
    }

    @Test
    void wrapsTheCommandBusOutermost() {
        assertThat(axon.getComponent(CommandBus.class)).isInstanceOf(VirtualThreadCommandBus.class);
    }

    @Test
    void runsCommandHandlersOnVirtualThreads() {
        // when
        axon.getComponent(CommandGateway.class).sendAndWait(new Ping("1"));

        // then
        assertThat(handler.threads).singleElement().satisfies(thread -> assertThat(thread.isVirtual()).isTrue());
    }

    @Test
    void runsDispatchInterceptorsOnTheVirtualThreadOfTheCommand() {
        // when
        axon.getComponent(CommandGateway.class).sendAndWait(new Ping("1"));

        // then: not on the caller's thread, so they cannot see its ThreadLocals
        assertThat(dispatchedOn).singleElement()
                                .satisfies(thread -> assertThat(thread.isVirtual()).isTrue())
                                .isSameAs(handler.threads.getFirst());
    }
}
