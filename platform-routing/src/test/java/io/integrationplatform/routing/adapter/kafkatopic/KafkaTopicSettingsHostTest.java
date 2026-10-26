package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** E16 Karan R4 (CWE-20/CWE-441): a stored row is re-checked so URI.create and Kafka's own parser cannot disagree. */
class KafkaTopicSettingsHostTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "good.partner.example:9092,x.partner.example?169.254.169.254:9092",
        "x.partner.example#169.254.169.254:9092",
        "user@10.0.0.5:9092",
        "x.partner.example/path:9092",
        "x.partner.example :9092",
        "a b.partner.example:9092",
        "tab\there.partner.example:9092",
        "http:host.partner.example:9092",
        "[::1]:9092",
        "kafka://x.partner.example:9092"})
    @Tag("security")
    @Tag("negative")
    void aBootstrapEntryWithUrlSyntaxIsRefused(String servers) {
        assertThatThrownBy(() -> new KafkaTopicSettings(servers, "orders")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void plainHostsAndAddressLiteralsAreStillAccepted() {
        assertThat(new KafkaTopicSettings("a.partner.example:9092, b-1.partner.example:9093,93.184.216.34:9094",
                "orders").host()).isEqualTo("a.partner.example");
    }
}
