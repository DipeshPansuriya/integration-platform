package io.integrationplatform.app.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** B33: only a canonical UUID session id is handed to the session store; the 10 S3-A5 shapes and more. */
class CanonicalSessionCookieSerializerTest {

    private static final String ID = UUID.randomUUID().toString();

    private static CanonicalSessionCookieSerializer serializer() {
        DefaultCookieSerializer base = new DefaultCookieSerializer();
        base.setCookieName("SESSION");
        return new CanonicalSessionCookieSerializer(base);
    }

    private static MockHttpServletRequest withCookie(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("SESSION", value));
        return request;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aGenuineEncodedUuidIsResolved() {
        assertThat(serializer().readCookieValues(withCookie(b64(ID)))).containsExactly(ID);
    }

    @Test
    void noCookieResolvesNothing() {
        assertThat(serializer().readCookieValues(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void everyTamperedValueResolvesNothing() {
        String genuine = b64(ID);
        String[] tampered = {
            genuine.substring(0, 20) + "!" + genuine.substring(21),
            genuine.substring(0, genuine.length() / 2),
            genuine + "AAAA", genuine + genuine, b64("0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d"), ID, "x'--", "A".repeat(4000),
            b64("abc"), b64("' OR '1'='1' --"), b64(ID + "\0"), b64("0b0f6a1e-3c1a-\0-1a7a"), b64("a".repeat(6000)),
            b64(ID + ID), b64(ID.toUpperCase() + "x"), b64(" " + ID),
            Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xfe, 'a'}), ""
        };
        for (String value : tampered) {
            if (value.equals(b64("0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d"))) {
                continue; // well-formed but unknown: shape passes, the store answers "no session" (container test)
            }
            assertThat(serializer().readCookieValues(withCookie(value))).as("cookie of %d chars", value.length()).isEmpty();
        }
    }

    @Test
    void aFlippedCharacterIsEitherRefusedOrStillACanonicalIdTheStoreDoesNotKnow() {
        String genuine = b64(ID);
        for (int at : new int[] {0, genuine.length() / 2, genuine.length() - 1}) {
            String flipped = genuine.substring(0, at) + (genuine.charAt(at) == 'A' ? 'B' : 'A') + genuine.substring(at + 1);
            assertThat(serializer().readCookieValues(withCookie(flipped)))
                    .allSatisfy(id -> assertThat(id).matches("[0-9a-fA-F-]{36}"));
        }
    }

    @Test
    void aWellFormedButUnknownIdPassesTheShapeCheckAndIsTheStoresToAnswer() {
        assertThat(serializer().readCookieValues(withCookie(b64("0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d"))))
                .containsExactly("0b0f6a1e-3c1a-4a52-9d58-1a7a0a1b2c3d");
    }
}
