package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Row 544 (L4, unit half): the relay-mode flag defaults to DEFINER and rejects anything it does not know. */
class RelayModeTest {

    @Test
    void unsetOrBlankIsTheDefinerDefault() {
        assertThat(RelayMode.parse(null)).isEqualTo(RelayMode.DEFINER);
        assertThat(RelayMode.parse("  ")).isEqualTo(RelayMode.DEFINER);
    }

    @Test
    void bothNamedModesParseIgnoringCaseAndPadding() {
        assertThat(RelayMode.parse("DEFINER")).isEqualTo(RelayMode.DEFINER);
        assertThat(RelayMode.parse(" legacy ")).isEqualTo(RelayMode.LEGACY);
    }

    @Test
    @Tag("negative")
    void anyOtherValueFailsTheStartInsteadOfFallingBack() {
        assertThatThrownBy(() -> RelayMode.parse("OFF")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEFINER or LEGACY");
    }
}
