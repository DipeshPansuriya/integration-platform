package io.integrationplatform.config.flow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T-066 / E12-F1-S2 (row 278 T108): the flow's masked-field list rides into the immutable interface snapshot, and a
 * flow without one keeps the exact bytes it had before (the byte-for-byte suites). Not tagged container (R03).
 */
class FlowBundleMaskedFieldsTest {

    @Test
    void theMaskedFieldsListIsPartOfTheSnapshotInterfacePart() {
        String snapshot = FlowBundle.serialize("flow-1", "Orders", "PER_KEY", "$.orderId",
                List.of("customerId"), List.of(), List.of());

        assertThat(snapshot).contains("\"businessKeyRule\":\"$.orderId\",\"maskedFields\":[\"customerId\"]}");
        assertThat(FlowBundle.maskedFields(snapshot)).containsExactly("customerId");
    }

    @Test
    void aFlowWithoutMaskedFieldsKeepsItsSnapshotBytesUnchanged() {
        String before = FlowBundle.serialize("flow-1", "Orders", "PER_KEY", "$.orderId", List.of(), List.of());
        String after = FlowBundle.serialize("flow-1", "Orders", "PER_KEY", "$.orderId", List.of(), List.of(),
                List.of());

        assertThat(after).isEqualTo(before).doesNotContain("maskedFields");
        assertThat(FlowBundle.maskedFields(before)).isEmpty();
    }
}
