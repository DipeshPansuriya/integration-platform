package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.flow.api.MaskedFields;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T-066 / E12-F1-S2 (row 278 T108): the failures read never hands a masked field's value back - a target that echoes
 * the payload in its error answer has that value replaced in {@code lastError}, from the message's own payload and the
 * masked-field list of the interface version it was accepted on. Not tagged container (R03): the store is a mock.
 */
class MessageTrackingMaskingTest {

    private static final String PROBE = "MASK-PROBE-7Q3Z";

    private static JdbcMessageTrackingStore storeWithOneDeadLetter(String messageId, String attemptDetail) {
        JdbcMessageTrackingStore store = mock(JdbcMessageTrackingStore.class);
        when(store.routeFailures(any(), any(), any())).thenReturn(List.of());
        when(store.deadLetters(any(), any(), any())).thenReturn(List.of(new JdbcMessageTrackingStore.DeadLetterRow(
                UUID.randomUUID().toString(), messageId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "PERMANENT", attemptDetail, "PERMANENT", Instant.now())));
        return store;
    }

    @Test
    void theLastErrorOfAFailureCarriesNoMaskedValue() {
        String messageId = UUID.randomUUID().toString();
        JdbcMessageTrackingStore store = storeWithOneDeadLetter(messageId, "HTTP 400 customerId " + PROBE + " unknown");
        when(store.maskedSource(messageId)).thenReturn(Optional.of(new JdbcMessageTrackingStore.MaskedSource(
                "{\"customerId\":\"" + PROBE + "\",\"orderId\":\"o-1\"}", "[\"customerId\"]")));

        List<MessageTrackingService.Failure> failures = new MessageTrackingService(store).failures(null, null);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).lastError()).isEqualTo("HTTP 400 customerId " + MaskedFields.MARKER + " unknown");
    }

    @Test
    void aFailureOfAFlowWithoutMaskedFieldsKeepsItsLastErrorAsIs() {
        String messageId = UUID.randomUUID().toString();
        JdbcMessageTrackingStore store = storeWithOneDeadLetter(messageId, "HTTP 400 field orderId rejected");
        when(store.maskedSource(messageId)).thenReturn(Optional.empty());

        assertThat(new MessageTrackingService(store).failures(null, null).get(0).lastError())
                .isEqualTo("HTTP 400 field orderId rejected");
    }
}
