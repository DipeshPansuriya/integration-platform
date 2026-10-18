package io.integrationplatform.routing.templates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Row 278 B22, Kavita L1 (CWE-209/79/117): a refusal echoes the scheme only if it is a URI scheme
 * ({@code ^[a-z][a-z0-9+.-]{0,31}$}) and the target type only if it is a short identifier
 * ({@code ^[A-Za-z][A-Za-z0-9_.-]{0,31}$}); anything else becomes a fixed text.
 */
@Tag("e9-t116")
class RefusalTextTest {

    @Test
    void aUriSchemeIsEchoedAsIs() {
        assertThat(RefusalText.scheme("exec")).isEqualTo("exec");
        assertThat(RefusalText.scheme("svn+ssh")).isEqualTo("svn+ssh");
        assertThat(RefusalText.scheme("a".repeat(32))).as("32 characters is the bound").isEqualTo("a".repeat(32));
    }

    @Test
    void textThatIsNotAUriSchemeBecomesTheFixedText() {
        for (String notAScheme : new String[] {null, "", "token@host", "user:pw", "9http", "a".repeat(33),
                "ex\r\nec", "exec\n", "ex ec"}) {
            assertThat(RefusalText.scheme(notAScheme)).as("scheme %s", notAScheme).isEqualTo("(not a URI scheme)");
        }
    }

    @Test
    void aShortIdentifierTypeIsEchoedAsIs() {
        assertThat(RefusalText.targetType("FTP")).isEqualTo("FTP");
        assertThat(RefusalText.targetType("api_v2.x-1")).isEqualTo("api_v2.x-1");
    }

    @Test
    void anythingElseAsATypeBecomesTheFixedText() {
        for (String notAType : new String[] {null, "", "X".repeat(33), "FTP\r\nX-Injected: yes", "FTP\n", "1FTP",
                "FTP type", "<script>"}) {
            assertThat(RefusalText.targetType(notAType)).as("type %s", notAType).isEqualTo("(not a target type)");
        }
    }
}
