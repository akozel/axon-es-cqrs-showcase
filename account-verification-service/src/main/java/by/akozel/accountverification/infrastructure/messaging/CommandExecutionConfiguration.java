package by.akozel.accountverification.infrastructure.messaging;

import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.CommandBus;

/**
 * Runs commands on virtual threads, at most {@link CommandExecutionSettings#maxConcurrentCommands()} at once, by
 * wrapping the command bus in a {@link VirtualThreadCommandBus}.
 */
public final class CommandExecutionConfiguration {

    private CommandExecutionConfiguration() {}

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer,
                                                    CommandExecutionSettings settings) {
        return configurer.componentRegistry(registry -> registry.registerDecorator(
                DecoratorDefinition.forType(CommandBus.class)
                                   .with((config, name, delegate) -> new VirtualThreadCommandBus(delegate, settings))
                                   .order(VirtualThreadCommandBus.DECORATION_ORDER)
                                   // Before subscribing processors unregister and the database is closed, so that
                                   // the commands still executing can complete.
                                   .onShutdown(Phase.INBOUND_COMMAND_CONNECTOR, bus -> bus.shutdown())));
    }
}
