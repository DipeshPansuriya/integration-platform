package io.integrationplatform.delivery.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * T-180: what the reconciler needs to know about a target endpoint - its call timeout (the stale-claim limit of
 * ADR-010 section 6 is derived from it) and whether it declares idempotent receive (ADR-011: only then may an
 * ambiguous send be retried). The configuration module owns the values; this port reads them.
 */
@FunctionalInterface
public interface TargetSendFacts {

    record Facts(Duration timeout, boolean idempotentReceive) {
    }

    Optional<Facts> forTarget(UUID targetEndpointId);

    /** Nothing known: the reconciler assumes the longest configurable timeout and no idempotent receive. */
    static TargetSendFacts unknown() {
        return target -> Optional.empty();
    }
}
