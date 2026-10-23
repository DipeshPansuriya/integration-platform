package io.integrationplatform.app.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T204: the callback's value is validated at startup (it reaches PostgreSQL as a bind value, never as SQL text). */
class MigrationLockTimeoutCallbackTest {

    @ParameterizedTest
    @ValueSource(strings = {"5s", "500ms", "1min", "30", "0", " 5s "})
    @DisplayName("a PostgreSQL duration (digits, optional ms/s/min) is accepted")
    void acceptsDurations(String value) {
        assertThat(new MigrationLockTimeoutCallback(value)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "five", "-1s", "5 s; DROP TABLE x", "5s'--", "1.5s", "5h"})
    @DisplayName("anything else stops the start, naming the property")
    void refusesAnythingElse(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new MigrationLockTimeoutCallback(value))
                .withMessageContaining("ipaas.flyway.lock-timeout");
    }

    @Test
    @DisplayName("it runs before each migration, inside that migration's transaction (SET LOCAL semantics)")
    void runsBeforeEachMigrateInTheTransaction() {
        MigrationLockTimeoutCallback callback = new MigrationLockTimeoutCallback("5s");
        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, null)).isTrue();
        assertThat(callback.supports(Event.AFTER_MIGRATE, null)).isFalse();
        assertThat(callback.canHandleInTransaction(Event.BEFORE_EACH_MIGRATE, null)).isTrue();
    }
}
