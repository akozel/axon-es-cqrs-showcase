package by.akozel.accountverification.infrastructure;

import java.util.Map;

/**
 * How many commands may execute at once: {@code COMMAND_CONCURRENCY_PER_CPU} for every processor available to the
 * JVM. A command beyond that is rejected at once, see {@link VirtualThreadCommandBus}.
 *
 * @param concurrencyPerCpu   commands admitted per available processor, from {@code COMMAND_CONCURRENCY_PER_CPU}
 * @param availableProcessors processors available to the JVM, read once at startup; it respects the CPU limit of a
 *                            container
 */
public record CommandExecutionSettings(int concurrencyPerCpu, int availableProcessors) {

    public static final String VARIABLE = "COMMAND_CONCURRENCY_PER_CPU";
    public static final int DEFAULT_CONCURRENCY_PER_CPU = 1000;

    public CommandExecutionSettings {
        if (availableProcessors < 1) {
            throw new IllegalArgumentException("availableProcessors must be positive, was: " + availableProcessors);
        }
        if (concurrencyPerCpu < 1) {
            throw new IllegalStateException(
                    "Environment variable " + VARIABLE + " must be a positive integer, was: " + concurrencyPerCpu);
        }
        try {
            Math.multiplyExact(concurrencyPerCpu, availableProcessors);
        } catch (ArithmeticException e) {
            throw new IllegalStateException(
                    "Environment variable %s=%d for %d CPUs exceeds the maximum of %d concurrent commands"
                            .formatted(VARIABLE, concurrencyPerCpu, availableProcessors, Integer.MAX_VALUE));
        }
    }

    public static CommandExecutionSettings fromEnvironment() {
        return fromEnvironment(System.getenv(), Runtime.getRuntime().availableProcessors());
    }

    /** Reads the settings from the given variables, for the given number of processors. */
    public static CommandExecutionSettings fromEnvironment(Map<String, String> env, int availableProcessors) {
        String value = env.get(VARIABLE);
        if (value == null || value.isBlank()) {
            return new CommandExecutionSettings(DEFAULT_CONCURRENCY_PER_CPU, availableProcessors);
        }
        try {
            return new CommandExecutionSettings(Integer.parseInt(value.trim()), availableProcessors);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Environment variable " + VARIABLE + " must be a positive integer, was: " + value);
        }
    }

    /** The default concurrency for this machine's processors; for tests, which don't read the environment. */
    public static CommandExecutionSettings defaults() {
        return new CommandExecutionSettings(DEFAULT_CONCURRENCY_PER_CPU, Runtime.getRuntime().availableProcessors());
    }

    public int maxConcurrentCommands() {
        return concurrencyPerCpu * availableProcessors; // cannot overflow, see the constructor
    }

    @Override
    public String toString() {
        return "%d per CPU x %d CPUs = %d".formatted(concurrencyPerCpu, availableProcessors, maxConcurrentCommands());
    }
}
