package io.integrationplatform.ingest.internal.relay;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the relay raises the outbox-age alert (T-191, E8-F1-S3; ADR-005 Decision 12: "oldest due unpublished
 * outbox row older than 60 s"). Raised once when the threshold is crossed, again only after the backlog has
 * cleared; the {@code platform.alerts.fired} counter and the Prometheus rule on the age gauge carry it as well.
 */
@FunctionalInterface
public interface RelayAlerts {

    /** The oldest due unpublished row has waited {@code oldestAge}; {@code unpublishedRows} are due and unpublished. */
    void outboxAgeExceeded(Duration oldestAge, long unpublishedRows);

    /** The default: one ERROR line operators' log alerting matches on. */
    RelayAlerts LOG = new RelayAlerts() {
        private final Logger log = LoggerFactory.getLogger(RelayAlerts.class);

        @Override
        public void outboxAgeExceeded(Duration oldestAge, long unpublishedRows) {
            log.error("ALERT outbox_age: oldest due unpublished outbox row is {} s old, {} rows unpublished",
                    oldestAge.toSeconds(), unpublishedRows);
        }
    };
}
