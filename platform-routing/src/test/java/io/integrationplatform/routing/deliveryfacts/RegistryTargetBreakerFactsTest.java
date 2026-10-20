package io.integrationplatform.routing.deliveryfacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.delivery.port.TargetBreakerFacts;
import io.integrationplatform.delivery.port.TargetBreakerFacts.BreakerFacts;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E10 T-183 lane closure: the bridge reads the adapter's ONE live registry (never a second one) and the target's
 * {@code half_open_trial_calls} setting. The breaker side is the real Resilience4j registry driven through its
 * explicit state transitions - no container; the rows-side reading is one parameterised statement over
 * {@code target_endpoint.settings} (mirrors the existing {@code JdbcTargetEndpointViews} reader) with the team
 * default 3 whenever the key is absent, empty, unreadable or out of the saved schema's 1-100. Not tagged
 * container (R03).
 */
class RegistryTargetBreakerFactsTest {

    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID UNKNOWN = UUID.randomUUID();

    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
    private final JdbcClient jdbc = mock(JdbcClient.class);

    private RegistryTargetBreakerFacts facts(Optional<String> settingValue) {
        return new RegistryTargetBreakerFacts(breakers, jdbcWithTrialCalls(settingValue));
    }

    /** The registry only ever answers a breaker for a name a send has keyed before: the unknown target is absent. */
    private void liveBreakerFor(String key) {
        breakers.circuitBreaker(key);
    }

    @Test
    @DisplayName("No live breaker for the target on this instance: no cap - unknown targets re-drive as always")
    void unknownTargetIsNeverCapped() {
        assertThat(facts(Optional.empty()).forTarget(UNKNOWN)).isEmpty();
    }

    @Test
    @DisplayName("A CLOSED (or never-failed) breaker caps nothing")
    void closedBreakerCapsNothing() {
        liveBreakerFor(TARGET.toString());

        assertThat(facts(Optional.empty()).forTarget(TARGET))
                .as("the port's no-cap answer for a closed breaker").isEmpty();
    }

    @Test
    @DisplayName("OPEN reports the trial-call cap the target's settings declare")
    void openBreakerCarriesTheTargetTrialCount() {
        breakers.circuitBreaker(TARGET.toString()).transitionToOpenState();

        var answer = facts(Optional.of("5")).forTarget(TARGET);

        assertThat(answer).contains(new BreakerFacts(BreakerFacts.State.OPEN, 5));
    }

    @Test
    @DisplayName("HALF_OPEN: one window - the cap is that window's trial-call count")
    void halfOpenCarriesTheTrialCount() {
        var breaker = breakers.circuitBreaker(TARGET.toString());
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();

        var answer = facts(Optional.of("3")).forTarget(TARGET);

        assertThat(answer).contains(new BreakerFacts(BreakerFacts.State.HALF_OPEN, 3));
    }

    @Test
    @DisplayName("A re-opened (FORCED_OPEN) breaker is still OPEN for the A4 re-drive cap")
    void forcedOpenIsOpenForTheCap() {
        breakers.circuitBreaker(TARGET.toString()).transitionToForcedOpenState();

        assertThat(facts(Optional.of("2")).forTarget(TARGET))
                .contains(new BreakerFacts(BreakerFacts.State.OPEN, 2));
    }

    @Test
    @DisplayName("The setting rides the saved schema: absent, blank, unreadable or out of the 1-100 range is "
            + "the default 3")
    void unreadableSettingIsTheDefault() {
        breakers.circuitBreaker(TARGET.toString()).transitionToOpenState();

        assertThat(facts(Optional.empty()).forTarget(TARGET))
                .contains(new BreakerFacts(BreakerFacts.State.OPEN, RegistryTargetBreakerFacts.DEFAULT_TRIAL_CALLS));
        assertThat(facts(Optional.of("")).forTarget(TARGET))
                .contains(new BreakerFacts(BreakerFacts.State.OPEN, RegistryTargetBreakerFacts.DEFAULT_TRIAL_CALLS));
        assertThat(facts(Optional.of("abc")).forTarget(TARGET))
                .contains(new BreakerFacts(BreakerFacts.State.OPEN, RegistryTargetBreakerFacts.DEFAULT_TRIAL_CALLS));
        assertThat(facts(Optional.of("999")).forTarget(TARGET))
                .contains(new BreakerFacts(BreakerFacts.State.OPEN, RegistryTargetBreakerFacts.DEFAULT_TRIAL_CALLS));
        assertThat(facts(Optional.of("0")).forTarget(TARGET))
                .contains(new BreakerFacts(BreakerFacts.State.OPEN, RegistryTargetBreakerFacts.DEFAULT_TRIAL_CALLS));
    }

    private JdbcClient jdbcWithTrialCalls(Optional<String> value) {
        @SuppressWarnings("unchecked")
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<String> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(startsWith("select settings"))).thenReturn(spec);
        when(spec.param("id", TARGET.toString())).thenReturn(spec);
        when(spec.query(String.class)).thenReturn(mapped);
        when(mapped.optional()).thenReturn(value);
        return jdbc;
    }
}