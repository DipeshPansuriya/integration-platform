package io.integrationplatform.routing.deliveryfacts;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.delivery.port.TargetBreakerFacts;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E10 T-183 lane closure (ADR-010 sections 6 and 8; ADR-011 Decision 1 addendum): the routing-owned bridge that
 * makes the delivery-retry job's half-open cap live. It reads the LIVE registry the sends use (one breaker per
 * target endpoint, keyed {@code targetEndpointId.toString()} - {@code ApiAdapterConfiguration.apiTargetBreakers})
 * and the target's {@code half_open_trial_calls} from {@code target_endpoint.settings} (saved through
 * platform-config's validated settings schema). Read-only; it runs inside the delivery-retry job's tenant-bound
 * transaction, so row-level security applies to the settings read, and the routing module never holds a second
 * registry.
 *
 * <p>The port's answers: no live breaker for this target on this instance, no visible endpoint row (another
 * tenant) or a closed breaker -> no cap ({@link Optional#empty()}); OPEN (or FORCED_OPEN) and HALF_OPEN -> a
 * {@link BreakerFacts} whose trial-call count is the target's setting, defaulting to 3 (ADR-011) when the
 * setting is absent or unreadable. The trial count is read per call: the target's row may change between
 * passes, and the bridge holds no state of its own.
 */
public final class RegistryTargetBreakerFacts implements TargetBreakerFacts {

    /** ADR-011 Decision 1 addendum's default: {@code half_open_trial_calls} 3, one 30-minute idle route. */
    public static final int DEFAULT_TRIAL_CALLS = 3;

    private final CircuitBreakerRegistry breakers;
    private final JdbcClient jdbc;

    public RegistryTargetBreakerFacts(CircuitBreakerRegistry breakers, JdbcClient jdbc) {
        this.breakers = Objects.requireNonNull(breakers, "breakers");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<BreakerFacts> forTarget(UUID targetEndpointId) {
        Objects.requireNonNull(targetEndpointId, "targetEndpointId");
        return breakers.find(targetEndpointId.toString())
                .flatMap(breaker -> breaker.getState() == CircuitBreaker.State.CLOSED
                        ? Optional.empty()
                        : Optional.of(breaker))
                .map(breaker -> factsOf(breaker, targetEndpointId));
    }

    /** The target's trial cap, or the team default when the key is absent, empty, not a number, or out of range. */
    private int trialCallsOf(UUID targetEndpointId) {
        Optional<String> raw = jdbc.sql("select settings->>'halfOpenTrialCalls' from public.target_endpoint"
                        + " where id = :id::uuid")
                .param("id", targetEndpointId.toString())
                .query(String.class)
                .optional();
        if (raw.isEmpty() || raw.get() == null || raw.get().isBlank()) {
            return DEFAULT_TRIAL_CALLS;
        }
        try {
            int parsed = Integer.parseInt(raw.get().trim());
            return parsed >= 1 && parsed <= 100 ? parsed : DEFAULT_TRIAL_CALLS;
        } catch (NumberFormatException unreadable) {
            return DEFAULT_TRIAL_CALLS;
        }
    }

    private BreakerFacts factsOf(CircuitBreaker breaker, UUID targetEndpointId) {
        BreakerFacts.State state = switch (breaker.getState()) {
            case HALF_OPEN -> BreakerFacts.State.HALF_OPEN;
            case OPEN, FORCED_OPEN -> BreakerFacts.State.OPEN;
            default -> BreakerFacts.State.CLOSED;
        };
        return new BreakerFacts(state, trialCallsOf(targetEndpointId));
    }

    /** The wiring: the adapter's own live registry, the tenant-bound client of the caller's transaction. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Wiring {

        @org.springframework.context.annotation.Bean
        TargetBreakerFacts registryTargetBreakerFacts(CircuitBreakerRegistry breakers, JdbcClient jdbcClient) {
            return new RegistryTargetBreakerFacts(breakers, jdbcClient);
        }
    }
}