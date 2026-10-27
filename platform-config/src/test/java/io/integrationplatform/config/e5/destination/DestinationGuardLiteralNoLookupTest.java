package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Row 484, security finding L-2: {@code DestinationGuard.literal()} promised "parsed, never looked up" but called
 * {@code InetAddress.getByName} on any text holding a colon, so a caller-chosen name such as {@code zzz:foo} reached the
 * JDK resolver before the allow-list refusal. It now uses {@code InetAddress.ofLiteral} (as AdminBaseUri did, PR 29).
 * {@link RecordingResolverProvider} records what the JDK resolver is asked.
 */
class DestinationGuardLiteralNoLookupTest {

    private static DestinationGuard guard() {
        return new DestinationGuard(AllowList.of(List.of("partner.example")), host -> {
            throw new UnknownHostException(host);
        });
    }

    @Test
    @DisplayName("self-test: the resolver hook is active in this JVM, so 'not looked up' below is a real absence")
    void theHookSeesALookup() {
        assertThatThrownBy(() -> InetAddress.getByName("hook-self-test.invalid"))
                .isInstanceOf(UnknownHostException.class);

        assertThat(RecordingResolverProvider.LOOKED_UP).contains("hook-self-test.invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zzz:foo", "localhost:80", "xyz.com:80", "metadata.google.internal:80"})
    @DisplayName("a host text with a colon that is no IP literal is refused and never handed to the JDK resolver")
    void aColonTextThatIsNoLiteralIsNeverLookedUp(String text) {
        assertThatThrownBy(() -> guard().checkHost(text)).isInstanceOf(DestinationRefusedException.class);

        assertThat(RecordingResolverProvider.LOOKED_UP).doesNotContain(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]", "fd00::1", "::ffff:127.0.0.1", "127.0.0.1"})
    @DisplayName("a real IP literal is still parsed (not resolved) and still refused as an internal address")
    void aRealLiteralIsStillRefused(String literal) {
        assertThatThrownBy(() -> guard().checkHost(literal)).isInstanceOf(DestinationRefusedException.class);

        assertThat(RecordingResolverProvider.LOOKED_UP).doesNotContain(literal);
    }
}
