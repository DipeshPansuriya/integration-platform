package io.integrationplatform.app.composition;

import io.integrationplatform.delivery.port.TargetSendFacts;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * T-180 open item 1 (row 278): the delivery trigger's {@link TargetSendFacts}, read from the target's own
 * configuration row ({@code timeout_ms}, {@code idempotent_receive}) through the existing
 * {@link TargetEndpointViews} read port. An unknown target has no facts; the trigger then assumes the longest
 * timeout and no idempotency.
 */
final class TargetEndpointViewsSendFacts implements TargetSendFacts {

    private final TargetEndpointViews views;

    TargetEndpointViewsSendFacts(TargetEndpointViews views) {
        this.views = Objects.requireNonNull(views, "views");
    }

    @Override
    public Optional<Facts> forTarget(UUID targetEndpointId) {
        return views.byEndpointId(targetEndpointId)
                .map(view -> new Facts(Duration.ofMillis(view.timeoutMs()), view.idempotentReceive()));
    }
}
