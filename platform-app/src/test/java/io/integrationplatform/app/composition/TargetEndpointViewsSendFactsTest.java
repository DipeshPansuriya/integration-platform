package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.app.scheduler.SchedulerJobTasks;
import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.routing.adapter.api.TargetEndpointView;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T-180 open item 1 (row 278): the delivery trigger's {@link TargetSendFacts} read the target's own
 * {@code timeout_ms} and {@code idempotent_receive} from the configuration rows, through the existing
 * {@link TargetEndpointViews} read port - no second query, no new library.
 */
class TargetEndpointViewsSendFactsTest {

    private static final UUID KNOWN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final TargetEndpointViews views = id -> KNOWN.equals(id)
            ? Optional.of(new TargetEndpointView(URI.create("https://target.example"), "POST", 4_500, "NO_AUTH",
                    null, null, true, Map.of()))
            : Optional.empty();

    @Test
    void mapsTheTargetsTimeoutAndIdempotentReceive() {
        TargetSendFacts facts = new TargetEndpointViewsSendFacts(views);

        assertThat(facts.forTarget(KNOWN))
                .contains(new TargetSendFacts.Facts(Duration.ofMillis(4_500), true));
    }

    @Test
    void anUnknownTargetHasNoFacts() {
        assertThat(new TargetEndpointViewsSendFacts(views).forTarget(UNKNOWN)).isEmpty();
    }

    @Test
    void theConfigurationExposesTheAdapterAsTheSendFactsBean() {
        assertThat(new DeliverySendFactsConfiguration().targetSendFacts(views))
                .isInstanceOf(TargetEndpointViewsSendFacts.class);
    }

    @Test
    void theSchedulerJobTypeMatchesTheDeliveryHandler() {
        assertThat(SchedulerJobTasks.DELIVERY_RETRY).isEqualTo("DELIVERY_RETRY");
    }
}
