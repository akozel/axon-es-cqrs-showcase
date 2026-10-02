package by.akozel.accountverification.infrastructure;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs commands on virtual threads and admits at most {@link CommandExecutionSettings#maxConcurrentCommands()} of
 * them at once; one more is rejected immediately with a {@link CommandConcurrencyLimitExceededException}.
 * <p>
 * Only commands sent without a {@link ProcessingContext}, i.e. by an entry point, are admitted this way. A command
 * sent from inside a handler, with the handler's context, belongs to a command that was already admitted: it runs on
 * the sender's thread and takes no permit.
 * <p>
 * An admitted command runs on the caller's thread when that already is a virtual thread, otherwise on a new one. Its
 * unit of work then stays on that thread ({@link JpaUnitOfWorkTransactionManager#requiresSameThreadInvocations()}).
 */
public class VirtualThreadCommandBus implements CommandBus {

    /** Outside Axon's own decorators (InterceptingCommandBus, RetryingCommandBus) and those of the default order 0. */
    public static final int DECORATION_ORDER = Integer.MAX_VALUE - 100;

    private static final Logger logger = LoggerFactory.getLogger(VirtualThreadCommandBus.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final CommandBus delegate;
    private final CommandExecutionSettings settings;
    private final Duration shutdownTimeout;
    private final Semaphore permits;
    private final ThreadFactory virtualThreads = Thread.ofVirtual().name("command-", 0).factory();
    private volatile boolean accepting = true;

    public VirtualThreadCommandBus(CommandBus delegate, CommandExecutionSettings settings) {
        this(delegate, settings, SHUTDOWN_TIMEOUT);
    }

    VirtualThreadCommandBus(CommandBus delegate, CommandExecutionSettings settings, Duration shutdownTimeout) {
        this.delegate = delegate;
        this.settings = settings;
        this.shutdownTimeout = shutdownTimeout;
        this.permits = new Semaphore(settings.maxConcurrentCommands());
    }

    @Override
    public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                            @Nullable ProcessingContext processingContext) {
        if (processingContext != null) {
            return delegate.dispatch(command, processingContext); // sent from a handler: part of an admitted command
        }
        if (!accepting) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Command " + command.type() + " was rejected: the application is shutting down"));
        }
        if (!permits.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new CommandConcurrencyLimitExceededException(command.type(), settings));
        }
        CompletableFuture<CommandResultMessage> result = new CompletableFuture<>();
        if (Thread.currentThread().isVirtual()) {
            relay(dispatchHoldingPermit(command), result);
            return result;
        }
        try {
            virtualThreads.newThread(() -> relay(dispatchHoldingPermit(command), result)).start();
        } catch (Throwable e) {
            permits.release();
            return CompletableFuture.failedFuture(e);
        }
        return result;
    }

    /** Dispatches an admitted command and returns its permit once it completes, however it ends. */
    private CompletableFuture<CommandResultMessage> dispatchHoldingPermit(CommandMessage command) {
        CompletableFuture<CommandResultMessage> dispatched;
        try {
            dispatched = delegate.dispatch(command, null);
        } catch (Throwable e) { // an Error that escaped would leave the caller waiting forever
            dispatched = CompletableFuture.failedFuture(e);
        }
        return dispatched.whenComplete((ignored, failure) -> permits.release());
    }

    private static void relay(CompletableFuture<CommandResultMessage> source,
                              CompletableFuture<CommandResultMessage> target) {
        source.whenComplete((result, failure) -> {
            if (failure == null) {
                target.complete(result);
            } else {
                target.completeExceptionally(
                        failure instanceof CompletionException && failure.getCause() != null
                                ? failure.getCause()
                                : failure);
            }
        });
    }

    @Override
    public VirtualThreadCommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
        delegate.subscribe(name, commandHandler);
        return this;
    }

    /**
     * Stops admitting commands sent without a context and waits, at most a few seconds, for the admitted ones to
     * finish. Commands sent from inside a handler are still accepted, so that the admitted ones can complete.
     */
    void shutdown() {
        accepting = false;
        try {
            if (!permits.tryAcquire(settings.maxConcurrentCommands(), shutdownTimeout.toMillis(),
                                    TimeUnit.MILLISECONDS)) {
                logger.warn("Stopped waiting for {} executing commands after {}",
                            settings.maxConcurrentCommands() - permits.availablePermits(), shutdownTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("maxConcurrentCommands", settings.toString());
    }
}
