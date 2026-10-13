package io.integrationplatform.routing.stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the stage consumers raise their alerts (T-191, E8-F1-S3; ADR-005 Decisions 8 and 12): every poison
 * arrival, and a consumer group too far behind. The {@code platform.alerts.fired} counter and the Prometheus
 * rules ({@code config/messaging-alert-rules.rules}) carry the same alerts for operators.
 */
public interface StageAlerts {

    /** A record of {@code group} went to the poison topic - fires on every arrival. */
    void poisonArrived(String group, String topic, int partition, long offset, String reason);

    /** {@code alert} names a group more than the thresholds behind and its partitions. */
    void lagExceeded(LagAlert alert);

    /** The default: one ERROR line per alert that the operators' log alerting matches on. */
    StageAlerts LOG = new StageAlerts() {
        private final Logger log = LoggerFactory.getLogger(StageAlerts.class);

        @Override
        public void poisonArrived(String group, String topic, int partition, long offset, String reason) {
            log.error("ALERT poison_arrival: group={} topic={} partition={} offset={} reason={}", group, topic,
                    partition, offset, reason);
        }

        @Override
        public void lagExceeded(LagAlert alert) {
            log.error("ALERT consumer_lag: group={} partitions={} lag={} stale={}", alert.group(),
                    alert.partitions(), alert.totalLag(), alert.stalePartitions());
        }
    };
}
