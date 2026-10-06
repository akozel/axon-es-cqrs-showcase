package by.akozel.accountverification.infrastructure.messaging;

import org.axonframework.common.AxonTransientException;
import org.axonframework.messaging.core.MessageType;

/**
 * A command was rejected because {@link CommandExecutionSettings#maxConcurrentCommands()} commands were already
 * executing. Transient: the same command may succeed when it is sent again later.
 */
public class CommandConcurrencyLimitExceededException extends AxonTransientException {

    public CommandConcurrencyLimitExceededException(MessageType commandType, CommandExecutionSettings settings) {
        super("Command %s was rejected: %d commands are already executing (%s=%d per CPU x %d CPUs)".formatted(
                commandType, settings.maxConcurrentCommands(), CommandExecutionSettings.VARIABLE,
                settings.concurrencyPerCpu(), settings.availableProcessors()));
    }
}
