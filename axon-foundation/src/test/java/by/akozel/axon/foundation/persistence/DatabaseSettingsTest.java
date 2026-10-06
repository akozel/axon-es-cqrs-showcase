package by.akozel.axon.foundation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DatabaseSettingsTest {

    private static final Map<String, String> REQUIRED = Map.of(
            "POSTGRES_DB", "event_store", "POSTGRES_USER", "axon", "POSTGRES_PASSWORD", "secret");

    @Test
    void boundsLockWaitsAndIdleTransactionsByDefault() {
        // when
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(REQUIRED);

        // then
        assertThat(settings.sessionOptions())
                .isEqualTo("-c lock_timeout=5s -c idle_in_transaction_session_timeout=60s");
    }

    @Test
    void takesTheTimeoutsFromTheEnvironment() {
        // given
        Map<String, String> env = new HashMap<>(REQUIRED);
        env.put("POSTGRES_LOCK_TIMEOUT", "500ms");
        env.put("POSTGRES_IDLE_IN_TRANSACTION_TIMEOUT", "0");

        // when
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(env);

        // then
        assertThat(settings.sessionOptions())
                .isEqualTo("-c lock_timeout=500ms -c idle_in_transaction_session_timeout=0");
    }

    @Test
    void rejectsATimeoutThatIsNotAPostgresDuration() {
        // given: anything else would end up as extra session options
        Map<String, String> env = new HashMap<>(REQUIRED);
        env.put("POSTGRES_LOCK_TIMEOUT", "5s -c work_mem=1GB");

        // when / then
        assertThatThrownBy(() -> DatabaseSettings.fromEnvironment(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTGRES_LOCK_TIMEOUT");
    }
}
