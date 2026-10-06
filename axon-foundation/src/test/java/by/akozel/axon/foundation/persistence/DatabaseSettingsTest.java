package by.akozel.axon.foundation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void usesTheDefaultTimeoutsForBlankValues() {
        // given
        Map<String, String> env = new HashMap<>(REQUIRED);
        env.put("POSTGRES_LOCK_TIMEOUT", " ");
        env.put("POSTGRES_IDLE_IN_TRANSACTION_TIMEOUT", "");

        // when
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(env);

        // then
        assertThat(settings.sessionOptions())
                .isEqualTo("-c lock_timeout=5s -c idle_in_transaction_session_timeout=60s");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"})
    void requiresTheDatabaseAndCredentials(String name) {
        // given
        Map<String, String> missing = new HashMap<>(REQUIRED);
        missing.remove(name);
        Map<String, String> blank = new HashMap<>(REQUIRED);
        blank.put(name, "  ");

        // when / then
        for (Map<String, String> env : List.of(missing, blank)) {
            assertThatThrownBy(() -> DatabaseSettings.fromEnvironment(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name)
                    .hasMessageContaining(".env.example");
        }
    }

    @Test
    void connectsToLocalhostOnTheDefaultPortUnlessToldOtherwise() {
        // when
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(REQUIRED);

        // then
        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/event_store");
    }

    @Test
    void neverPrintsThePassword() {
        // when
        String description = DatabaseSettings.fromEnvironment(REQUIRED).toString();

        // then
        assertThat(description)
                .isEqualTo("DatabaseSettings[jdbc:postgresql://localhost:5432/event_store, user=axon]")
                .doesNotContain("secret");
    }
}
