package io.integrationplatform.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * E12-F2-S1 (row 327 T117): the configuration audit event's shape - who, what, when, tenant, target ids and
 * before/after - and the state text before/after carries, which escapes what it is given and holds only facts.
 */
class ConfigAuditActionsTest {

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID ACTOR = UUID.fromString("0000000c-0000-4000-8000-00000000000c");

    @Test
    void anEventCarriesWhoWhatWhenTenantTargetsAndBeforeAfter() {
        Instant before = Instant.now();

        AuditEvent event = ConfigAuditActions.event(ConfigAuditActions.FLOW_APPROVED, TENANT, ACTOR,
                List.of("interface:1", "interface_version:2"), "{\"status\":\"PENDING_APPROVAL\"}",
                "{\"status\":\"APPROVED\"}");

        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.who()).isEqualTo(ACTOR.toString());
        assertThat(event.action()).isEqualTo("FLOW_APPROVED");
        assertThat(event.targetIds()).containsExactly("interface:1", "interface_version:2");
        assertThat(event.before()).isEqualTo("{\"status\":\"PENDING_APPROVAL\"}");
        assertThat(event.after()).isEqualTo("{\"status\":\"APPROVED\"}");
        assertThat(event.at()).isBetween(before, Instant.now().plus(Duration.ofSeconds(1)));
    }

    @Test
    void anEventWithoutTenantActorActionOrTargetsIsRefused() {
        List<String> targets = List.of("interface:1");
        assertThatNullPointerException().isThrownBy(
                () -> ConfigAuditActions.event(ConfigAuditActions.FLOW_CREATED, null, ACTOR, targets, null, null));
        assertThatNullPointerException().isThrownBy(
                () -> ConfigAuditActions.event(ConfigAuditActions.FLOW_CREATED, TENANT, null, targets, null, null));
        assertThatNullPointerException().isThrownBy(
                () -> ConfigAuditActions.event(null, TENANT, ACTOR, targets, null, null));
        assertThatNullPointerException().isThrownBy(
                () -> ConfigAuditActions.event(ConfigAuditActions.FLOW_CREATED, TENANT, ACTOR, null, null, null));
    }

    @Test
    void aTargetIsKindColonId() {
        UUID id = UUID.randomUUID();
        assertThat(ConfigAuditActions.target("rule_set", id)).isEqualTo("rule_set:" + id);
        assertThatNullPointerException().isThrownBy(() -> ConfigAuditActions.target("rule_set", null));
    }

    @Test
    void stateIsAFlatJsonObjectOfEscapedFacts() {
        assertThat(ConfigAuditActions.state("status", "APPROVED", "version", 3, "pending", true, "gone", null,
                "fields", List.of("apiKey", "password")))
                .isEqualTo("{\"status\":\"APPROVED\",\"version\":3,\"pending\":true,\"gone\":null,"
                        + "\"fields\":[\"apiKey\",\"password\"]}");
        assertThat(ConfigAuditActions.state("name", "say \"hi\"\\\n\u0001"))
                .isEqualTo("{\"name\":\"say \\\"hi\\\"\\\\\\n\\u0001\"}");
        assertThat(ConfigAuditActions.state()).isEqualTo("{}");
    }
}
