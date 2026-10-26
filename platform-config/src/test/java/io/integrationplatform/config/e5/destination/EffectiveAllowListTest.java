package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.EffectiveAllowList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-184 Mahesh 1f (ADR-010 section 8; the effective destination allow-list): global AND tenant, tenant narrows.
 * Every case is a count of hosts that save as destinations and hosts that are refused. Not tagged container (R03).
 */
class EffectiveAllowListTest {

    private static final AllowList GLOBAL = AllowList.of(
            List.of("api.partner.example", "*.mq.example", "*.partner.example"));

    @Test
    @DisplayName("An empty (or missing) global list fails closed: NOTHING is a destination (T-202)")
    void emptyGlobalFailsClosed() {
        AllowList effective = EffectiveAllowList.of(AllowList.of(List.of()),
                List.of("everything.partner.example"));
        assertThat(effective.allows("everything.partner.example")).isFalse();
        assertThat(EffectiveAllowList.of(AllowList.of(List.of()), null).allows("api.partner.example")).isFalse();
    }

    @Test
    @DisplayName("A tenant that names none keeps the operator's bound alone - a bound, never a widened target set")
    void tenantWithoutPatternsKeepsTheGlobalBound() {
        AllowList effective = EffectiveAllowList.of(GLOBAL, List.of());
        assertThat(effective.allows("api.partner.example")).isTrue();
        assertThat(effective.allows("off.mq.example")).as("mq domain wildcard").isTrue();
        assertThat(effective.allows("stranger.example")).as("off the global bound").isFalse();
    }

    @Test
    @DisplayName("The tenant narrows: a name must pass BOTH lists - the exact pair is allowed")
    void bothListsMustAgree() {
        AllowList effective = EffectiveAllowList.of(GLOBAL, List.of("orders.partner.example"));
        assertThat(effective.allows("orders.partner.example")).isTrue();
        assertThat(effective.allows("api.partner.example")).as("global-only host, narrowed away").isFalse();
        assertThat(effective.allows("stranger.partner.example")).as("global wildcard, tenant exact: refused")
                .isFalse();
    }

    @Test
    @DisplayName("Wildcard in both: the tenant wildcard stands only while the global one covers it")
    void wildcardPairsCompose() {
        AllowList effective = EffectiveAllowList.of(GLOBAL, List.of("*.partner.example"));
        assertThat(effective.allows("anything.partner.example")).isTrue();
        assertThat(effective.allows("other.mq.example")).as("tenant's list never widens beyond both").isFalse();

        AllowList narrowed = EffectiveAllowList.of(GLOBAL, List.of("*.sub.mq.example"));
        assertThat(narrowed.allows("b.sub.mq.example")).isTrue();
        assertThat(narrowed.allows("base.mq.example")).isFalse();
    }

    @Test
    @DisplayName("The bare domain is never a wildcarded host on either side")
    void bareDomainStaysRefused() {
        assertThat(EffectiveAllowList.of(AllowList.of(List.of("*.mq.example")),
                List.of("mq.example")).allows("mq.example")).isFalse();
    }
}