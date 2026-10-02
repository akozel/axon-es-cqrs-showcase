package by.akozel.accountverification.infrastructure;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * PostgreSQL connection settings, read from the same {@code POSTGRES_*} variables that compose.yaml uses, plus the
 * session timeouts that only the application reads.
 *
 * @param lockTimeout              PostgreSQL {@code lock_timeout}: how long a statement may wait for a lock, e.g.
 *                                 a writer for another transaction's row with the same aggregate sequence number
 * @param idleInTransactionTimeout PostgreSQL {@code idle_in_transaction_session_timeout}: how long a transaction may
 *                                 stay open without running a statement before the session is terminated
 */
public record DatabaseSettings(String host, int port, String database, String user, String password,
                               String lockTimeout, String idleInTransactionTimeout) {

    /** A PostgreSQL time value without spaces, such as {@code 500ms}, {@code 5s} or {@code 0} (disabled). */
    private static final Pattern DURATION = Pattern.compile("\\d+(us|ms|s|min|h|d)?");

    public static DatabaseSettings fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /** Reads the settings from the given variables, e.g. the ones describing a test container. */
    public static DatabaseSettings fromEnvironment(Map<String, String> env) {
        return new DatabaseSettings(
                env.getOrDefault("POSTGRES_HOST", "localhost"),
                Integer.parseInt(env.getOrDefault("POSTGRES_PORT", "5432")),
                required(env, "POSTGRES_DB"),
                required(env, "POSTGRES_USER"),
                required(env, "POSTGRES_PASSWORD"),
                duration(env, "POSTGRES_LOCK_TIMEOUT", "5s"),
                duration(env, "POSTGRES_IDLE_IN_TRANSACTION_TIMEOUT", "60s"));
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(host, port, database);
    }

    /** The session timeouts in the form of the PostgreSQL JDBC driver's {@code options} connection property. */
    public String sessionOptions() {
        return "-c lock_timeout=%s -c idle_in_transaction_session_timeout=%s"
                .formatted(lockTimeout, idleInTransactionTimeout);
    }

    @Override
    public String toString() {
        return "DatabaseSettings[" + jdbcUrl() + ", user=" + user + "]"; // never print the password
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable " + name + " is not set. Copy .env.example to .env and load it.");
        }
        return value;
    }

    private static String duration(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if (!DURATION.matcher(value.trim()).matches()) {
            throw new IllegalStateException(
                    "Environment variable " + name + " must be a PostgreSQL duration such as 5s or 500ms, was: "
                            + value);
        }
        return value.trim();
    }
}
