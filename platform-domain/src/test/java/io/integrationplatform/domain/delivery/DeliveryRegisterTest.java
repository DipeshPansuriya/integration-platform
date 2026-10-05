package io.integrationplatform.domain.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Seam lock (E2, arjun-fullstack): the closed registers of ADR-010 sections 5 and 6 - the constant names are the
 * stored values (text + CHECK, never ENUM; R10), so renaming or adding one here changes the database contract, the
 * delivery-state diagram and every lane's code at once. A lane that needs a state or class added reports it to
 * arjun-fullstack; do not edit this test.
 */
class DeliveryRegisterTest {

    @Test
    void deliveryStateRegisterIsExactlyAdr010Section6() {
        Set<DeliveryState> expected = EnumSet.of(
                DeliveryState.PENDING,
                DeliveryState.HELD,
                DeliveryState.SENDING,
                DeliveryState.RETRY_WAIT,
                DeliveryState.DUE,
                DeliveryState.DELIVERED,
                DeliveryState.DEAD_LETTERED);
        assertThat(DeliveryState.values()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void outcomeClassRegisterIsExactlyAdr010Section5() {
        Set<OutcomeClass> expected = EnumSet.of(
                OutcomeClass.SUCCESS,
                OutcomeClass.TRANSIENT,
                OutcomeClass.AMBIGUOUS,
                OutcomeClass.PERMANENT);
        assertThat(OutcomeClass.values()).containsExactlyInAnyOrderElementsOf(expected);
    }
}
