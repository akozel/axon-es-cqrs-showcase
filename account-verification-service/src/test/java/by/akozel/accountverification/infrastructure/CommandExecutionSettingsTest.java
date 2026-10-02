package by.akozel.accountverification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandExecutionSettingsTest {

    @Test
    void allowsAThousandCommandsPerProcessorByDefault() {
        // when
        CommandExecutionSettings settings = CommandExecutionSettings.fromEnvironment(Map.of(), 3);

        // then
        assertThat(settings.concurrencyPerCpu()).isEqualTo(1000);
        assertThat(settings.maxConcurrentCommands()).isEqualTo(3000);
    }

    @Test
    void takesTheConcurrencyPerProcessorFromTheEnvironment() {
        // when
        CommandExecutionSettings settings = CommandExecutionSettings.fromEnvironment(
                Map.of(CommandExecutionSettings.VARIABLE, "250"), 4);

        // then
        assertThat(settings.maxConcurrentCommands()).isEqualTo(1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "ten", "1.5"})
    void rejectsAValueThatIsNotAPositiveInteger(String value) {
        // given
        Map<String, String> env = Map.of(CommandExecutionSettings.VARIABLE, value);

        // when / then
        assertThatThrownBy(() -> CommandExecutionSettings.fromEnvironment(env, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CommandExecutionSettings.VARIABLE);
    }

    @Test
    void rejectsALimitThatDoesNotFitAnInt() {
        // given
        Map<String, String> env = Map.of(CommandExecutionSettings.VARIABLE, String.valueOf(Integer.MAX_VALUE));

        // when / then
        assertThatThrownBy(() -> CommandExecutionSettings.fromEnvironment(env, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CommandExecutionSettings.VARIABLE);
    }

    @Test
    void describesTheLimitWithItsFactors() {
        // when
        String description = new CommandExecutionSettings(1000, 3).toString();

        // then
        assertThat(description).isEqualTo("1000 per CPU x 3 CPUs = 3000");
    }
}
