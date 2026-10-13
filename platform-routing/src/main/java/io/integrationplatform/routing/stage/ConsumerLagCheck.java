package io.integrationplatform.routing.stage;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import org.apache.kafka.clients.admin.Admin;

/** T-191 red skeleton: the consumer-lag check - not built yet. */
public final class ConsumerLagCheck {

    public ConsumerLagCheck(Admin admin, String group, Collection<String> topics, long maxLagRecords,
            Duration maxLagAge, Duration callTimeout, Clock clock, MeterRegistry meters, StageAlerts alerts) {
    }

    public Optional<LagAlert> check() {
        return Optional.empty();
    }
}
