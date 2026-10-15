package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * T-191 (E8-F1-S3; ADR-005 Decision 12 "Alerts"): the Prometheus rules the messaging metrics back - outbox age over
 * 60 s, consumer lag over 1,000 records or a record older than 2 minutes per group, and every poison arrival -
 * ship with the module, each on the metric the relay or the stage consumer actually registers.
 */
class MessagingAlertRulesTest {

    private static String rules() throws IOException {
        try (InputStream in = MessagingAlertRulesTest.class.getResourceAsStream(
                "/config/messaging-alert-rules.rules")) {
            assertThat(in).as("config/messaging-alert-rules.rules ships on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theOutboxAgeAlertFiresAfterSixtySeconds() throws IOException {
        assertThat(rules()).contains("alert: OutboxUnpublishedTooOld")
                .contains("platform_outbox_oldest_unpublished_age_seconds > 60");
    }

    @Test
    void theConsumerLagAlertFiresAboveOneThousandRecordsOrTwoMinutesPerGroup() throws IOException {
        assertThat(rules()).contains("alert: ConsumerLagTooHigh")
                .contains("sum by (group) (platform_stage_lag_records) > 1000")
                .contains("alert: ConsumerLagTooOld")
                .contains("platform_stage_lag_stale_partitions > 0");
    }

    @Test
    void everyPoisonArrivalFiresTheAlert() throws IOException {
        assertThat(rules()).contains("alert: PoisonArrival")
                .contains("increase(platform_stage_poison_arrivals_total[5m]) > 0");
    }

    @Test
    void aPublishFailureRateAboveFivePercentPerTopicFiresTheAlert() throws IOException {
        assertThat(rules()).contains("alert: PublishFailureRateHigh")
                .contains("sum by (topic) (rate(platform_publish_records_total{outcome=\"failure\"}[5m]))"
                        + " / sum by (topic) (rate(platform_publish_records_total[5m])) > 0.05");
    }

    @Test
    void publishFailuresWithNoSuccessForTenMinutesFireTheSustainedAlert() throws IOException {
        assertThat(rules()).contains("alert: PublishFailuresSustained")
                .contains("sum by (topic) (increase(platform_publish_records_total{outcome=\"failure\"}[10m])) > 0")
                .contains("unless sum by (topic) (increase(platform_publish_records_total{outcome=\"success\"}[10m]))"
                        + " > 0");
    }
}
